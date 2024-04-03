package autoprob;

import autoprob.go.Node;
import autoprob.go.parse.Parser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class GoTool {
    public static void main(String[] args) throws Exception {
        System.out.println("go tool start...");

        Properties prop = ExecBase.getRunConfig(args);

        GoTool gt = new GoTool();
        try {
            gt.runTool(prop);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Node loadPassedSgf(Properties prop) throws Exception {
        String path = prop.getProperty("path");
        if (path == null) {
            throw new RuntimeException("you must pass in a path");
        }
        File f = new File(path);
        if (!f.exists()) {
            throw new RuntimeException("no such file or directory: " + path);
        }
        String sgf;
        sgf = Files.readString(Path.of(path));

        // load SGF
        var parser = new Parser();
        Node node;
        node = parser.parse(sgf);

        return node;
    }

    private void runTool(Properties prop) throws Exception {
        String command = prop.getProperty("cmd");
        if (command == null) {
            throw new RuntimeException("you must pass in a cmd");
        }
        if (command.equals("extents")) {
            runExtentsCommand(prop);
        } else {
            throw new RuntimeException("unknown command: " + command);
        }
    }

    private void runExtentsCommand(Properties prop) throws Exception {
        Node node = loadPassedSgf(prop);
        System.out.println(node.board);
    }
}
