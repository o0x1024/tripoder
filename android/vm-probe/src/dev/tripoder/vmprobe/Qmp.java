package dev.tripoder.vmprobe;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Private Unix socket only. No TCP listener, guest networking or forwarded Android service. */
final class Qmp implements AutoCloseable {
    private final LocalSocket socket = new LocalSocket();
    private final BufferedReader input;
    private final Writer output;
    Qmp(File path) throws Exception {
        try {
        socket.connect(new LocalSocketAddress(path.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
        socket.setSoTimeout(5000);
        input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        output = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        String line = input.readLine();
        if (line == null || !new JSONObject(line).has("QMP")) throw new IOException("Invalid QMP greeting");
        execute("qmp_capabilities", null);
        } catch (Exception error) {
            try { socket.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }
    JSONObject execute(String command, JSONObject arguments) throws Exception {
        JSONObject request = new JSONObject().put("execute", command);
        if (arguments != null) request.put("arguments", arguments);
        output.write(request.toString() + "\n"); output.flush();
        for (int count = 0; count < 128; count++) {
            String line = input.readLine();
            if (line == null) throw new EOFException("QMP closed");
            JSONObject response = new JSONObject(line);
            if (response.has("error")) throw new IOException(response.getJSONObject("error").toString());
            if (response.has("return")) return response;
        }
        throw new IOException("QMP event limit exceeded");
    }
    public void close() throws IOException { socket.close(); }
}
