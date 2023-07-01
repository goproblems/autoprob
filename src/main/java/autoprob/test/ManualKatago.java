package autoprob.test;

import autoprob.ExecBase;
import autoprob.KataBrain;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.util.Properties;

public class ManualKatago {
    BufferedReader reader;
    private Properties props;
    private static final DecimalFormat df = new DecimalFormat("0.00");
    private Node node;

    public static void main(String[] args) throws Exception {
        System.out.println("viewtest");

        Properties prop = ExecBase.getRunConfig(args);

        var manual = new ManualKatago();
        manual.run(prop);
    }

    private void run(Properties props) throws Exception {
        this.props = props;
        // create a katago
        String kataPath = props.getProperty("katago").trim();
        String configPath = props.getProperty("kata.config").trim();
        String modelPath = props.getProperty("kata.model").trim();

        // create a native katago process
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.redirectErrorStream(true);
        processBuilder.command(kataPath, "analysis", "-config", configPath, "-model", modelPath);
        Process process;
        process = processBuilder.start();

        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        Thread thread = new Thread() {
            public void run() {
                System.out.println("katabrain Thread Running");
                try {
                    ManualKatago.this.processKataResponses();
                } catch (JsonSyntaxException | IOException e) {
                    e.printStackTrace();
                }
            }
        };

        thread.start();

        // read command from stdin
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        String line;
        Gson gson = new Gson();
        // loop and read lines
        PrintWriter pw = new PrintWriter(process.getOutputStream());
        while (true) {
            line = stdin.readLine();
            line = line.trim();
            if (line.length() > 0) {
                // first convert to a Node so we can capture the board
                KataQuery query = gson.fromJson(line, KataQuery.class);
                node = query.generateNode();
                System.out.println("moves:" + query.printMoves());
                // send to katago
                pw.println(line);
                pw.flush();
            }
        }
    }

    protected void processKataResponses() throws JsonSyntaxException, IOException {
        boolean printSummary = Boolean.parseBoolean(props.getProperty("kata.print_summary_result", "false"));
        Gson gson = new Gson();
        String line;
        long startTime = 0;
        int total = 0;
        boolean started = false;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("{\"error")) {
                System.out.println("bad analysis: " + line);
                //TODO: process error
                continue;
            }
            if (line.contains("terminating with"))
                throw new RuntimeException("ending on katago error: " + line);

            // print to debug
            System.out.println("kata: " + line);

            if (line.startsWith("{")) {
                KataAnalysisResult kres = gson.fromJson(line, KataAnalysisResult.class);
                total++;
                double avgTime = (System.currentTimeMillis() - startTime) / (double)total;
                System.out.println("> KBRAIN parsed: " + kres.id + ", turn: " + kres.turnNumber + ", score: " + df.format(kres.rootInfo.scoreLead) + ", avg ms: " + df.format(avgTime));

                System.out.println();
                System.out.println();

                System.out.println(kres.rootInfo);

                System.out.println("Ownership after move depth: " + node.depth + ":");
                kres.drawOwnership(node);
                kres.drawNumericalOwnership(node);

                System.out.println(kres.printMoves(1000));
            }
            else if (line.contains("ready to begin handling requests")) {
                System.out.println(line);
                startTime = System.currentTimeMillis();
                started = true;
            }
            else {
                if (started) {
                    // print out other lines
                    System.out.println(line);
                }
            }
        }
    }

}
