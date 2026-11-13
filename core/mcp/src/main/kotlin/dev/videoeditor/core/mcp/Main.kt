package dev.videoeditor.core.mcp

import dev.videoeditor.core.mcp.bridge.BridgeClient
import dev.videoeditor.core.mcp.bridge.BridgeEndpoint
import dev.videoeditor.core.mcp.bridge.BridgeException
import dev.videoeditor.timeline.json.TimelineJson
import dev.videoeditor.timeline.sample.SampleProjects
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """vedit-mcp ${McpServer.VERSION}: MCP server for Open Video Editor (stdio only)

Usage:
  vedit-mcp stdio [--bridge tcp:47821 | --bridge unix:/path/to.sock]
      Serve MCP on stdin/stdout and drive the phone through the authenticated bridge
      (reached with: adb forward tcp:47821 localabstract:dev.videoeditor.app.bridge).
  vedit-mcp stdio --project FILE.json [--workspace DIR]
      Offline mode: edit a project JSON file directly. No phone, no token; export is unsupported.
  vedit-mcp pair [--bridge ...] [--code 12345678] [--name NAME] [--uri vedit-pair://...]
      Pair with the phone (open 'Claude Code (MCP)' > 'Pair a new client' in the app first).
  vedit-mcp sample-project OUT.json
      Write the built-in two-clip sample project to OUT.json.

Credentials: ${'$'}VEDIT_MCP_CREDENTIALS, else ${'$'}XDG_CONFIG_HOME/vedit-mcp/credentials.json,
else ~/.config/vedit-mcp/credentials.json (mode 0600)."""

fun main(args: Array<String>) {
    val code =
        try {
            run(args.toList())
        } catch (e: IllegalArgumentException) {
            System.err.println("vedit-mcp: ${e.message}")
            2
        } catch (e: IOException) {
            System.err.println("vedit-mcp: ${e.message}")
            1
        } catch (e: BridgeException) {
            System.err.println("vedit-mcp: ${e.name}: ${e.message}")
            1
        }
    System.out.flush()
    exitProcess(code)
}

private fun options(args: List<String>): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        require(a.startsWith("--")) { "unexpected argument '$a'\n\n$USAGE" }
        val key = a.removePrefix("--").substringBefore('=')
        val value =
            if ('=' in a) {
                a.substringAfter('=')
            } else {
                i++
                args.getOrNull(i) ?: throw IllegalArgumentException("--$key needs a value")
            }
        out[key] = value
        i++
    }
    return out
}

private fun run(args: List<String>): Int {
    when (args.firstOrNull()) {
        null, "--help", "-h", "help" -> {
            System.err.println(USAGE)
            return if (args.isEmpty()) 2 else 0
        }
        "--version" -> {
            println(McpServer.VERSION)
            return 0
        }
        "stdio" -> {
            val o = options(args.drop(1))
            val unknown = o.keys - setOf("project", "workspace", "bridge")
            require(unknown.isEmpty()) { "unknown option(s) ${unknown.joinToString()}" }
            val backend: ProjectBackend =
                if (o["project"] != null) {
                    require(o["bridge"] == null) { "--project and --bridge are mutually exclusive" }
                    FileProjectBackend(PathGuard.resolveProject(o.getValue("project"), o["workspace"]))
                } else {
                    require(o["workspace"] == null) { "--workspace needs --project" }
                    val endpoint = BridgeEndpoint.parse(o["bridge"] ?: "tcp:${BridgeEndpoint.DEFAULT_PORT}")
                    val path = CredentialsFile.defaultPath()
                    BridgeProjectBackend(endpoint, { CredentialsFile.read(path) })
                }
            Log.info("serving MCP on stdio (${backend.description})")
            McpServer(backend).run(System.`in`, System.out)
            return 0
        }
        "pair" -> return pair(options(args.drop(1)))
        "sample-project" -> {
            val out = args.getOrNull(1) ?: throw IllegalArgumentException("sample-project needs an output path")
            Files.writeString(Path.of(out), TimelineJson.encodeProject(SampleProjects.twoClipDemo()))
            System.err.println("vedit-mcp: wrote $out")
            return 0
        }
        else -> throw IllegalArgumentException("unknown command '${args[0]}'\n\n$USAGE")
    }
}

private fun pair(o: Map<String, String>): Int {
    var endpointSpec = o["bridge"]
    var code = o["code"]
    o["uri"]?.let { raw ->
        val uri = URI(raw)
        require(uri.scheme == "vedit-pair") { "not a vedit-pair:// URI" }
        val q =
            uri.rawQuery
                .orEmpty()
                .split('&')
                .filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        if (code == null) code = q["code"]
        if (endpointSpec == null) q["port"]?.let { endpointSpec = "tcp:$it" }
    }
    val endpoint = BridgeEndpoint.parse(endpointSpec ?: "tcp:${BridgeEndpoint.DEFAULT_PORT}")
    if (code == null) {
        System.err.print("Enter the 8-digit code shown on the phone: ")
        System.err.flush()
        code = readlnOrNull()?.trim()
    }
    val c = code?.filter { it.isDigit() }
    require(c != null && c.length == 8) { "the pairing code has 8 digits" }
    val name = o["name"] ?: "claude-code@${runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("host")}"
    System.err.println("vedit-mcp: code sent; tap Allow on the phone (60 s)...")
    val creds = runBlocking { BridgeClient().pair(endpoint, c, name) }
    val path = CredentialsFile.defaultPath()
    CredentialsFile.write(path, creds)
    System.err.println(
        "vedit-mcp: paired as '$name' (scopes: ${creds.scopes.joinToString { it.wire }}); credentials saved to $path",
    )
    return 0
}
