package autoprob.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Properties;
import java.util.UUID;

/** Enqueues one finite precomputation using the worker's existing queue credentials. */
public final class JosekiPrecalculateCommand {
    private final Properties props;

    public JosekiPrecalculateCommand(Properties props) {
        this.props = props;
    }

    public void run() throws Exception {
        String origin = props.getProperty("joseki.baseurl", "").trim().replaceAll("/+$", "");
        if (origin.isEmpty()) throw new IllegalArgumentException("Configure joseki.baseurl for the Josekipedia API");
        String operationId = props.getProperty("joseki.precalculate.operation_id", UUID.randomUUID().toString()).trim();
        if (!operationId.matches("[a-zA-Z0-9-]{16,64}")) {
            throw new IllegalArgumentException("joseki.precalculate.operation_id must contain 16–64 letters, digits or hyphens");
        }
        JsonObject body = new JsonObject();
        if (props.containsKey("joseki.precalculate.path")) body.addProperty("path", props.getProperty("joseki.precalculate.path"));
        if (props.containsKey("joseki.precalculate.path_prefix")) body.addProperty("pathPrefix", props.getProperty("joseki.precalculate.path_prefix"));
        if (props.containsKey("joseki.precalculate.target_client_ids")) {
            JsonArray targets = new JsonArray();
            for (String value : props.getProperty("joseki.precalculate.target_client_ids").split(",")) {
                if (!value.isBlank()) targets.add(value.trim());
            }
            body.add("targetClientIds", targets);
        }
        System.out.println("Submitting josekiprecalculate; operation=" + operationId);
        JsonObject task = new TaskQueueClient(props).requestAt(origin + "/api/joseki-precalculations/" + operationId, "POST", body);
        System.out.printf("Queued josekiprecalculate task %d (%d snapshot nodes); unified workers will process it.%n",
            task.get("id").getAsLong(), task.get("totalNodes").getAsLong());
    }
}
