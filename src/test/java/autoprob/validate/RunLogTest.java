package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLogTest {
    @TempDir
    Path tmp;

    @Test
    void theFileIsNamedByTheRunsStart() {
        assertEquals(new File(new File("crawl"), "logs/20261123-104507.log"),
                RunLog.file(new File("crawl"), LocalDateTime.of(2026, 11, 23, 10, 45, 7)));
    }

    @Test
    void whatIsPrintedGoesToTheConsoleAsBeforeAndToTheFileWithItsTime() throws Exception {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream console = new ByteArrayOutputStream(), errors = new ByteArrayOutputStream();
        PrintStream consoleOut = new PrintStream(console, true, StandardCharsets.UTF_8);
        PrintStream consoleErr = new PrintStream(errors, true, StandardCharsets.UTF_8);
        File file = tmp.resolve("logs/run.log").toFile();
        LocalDateTime[] now = {LocalDateTime.of(2026, 11, 23, 10, 45, 7)};
        try {
            System.setOut(consoleOut);
            System.setErr(consoleErr);
            try (RunLog log = RunLog.open(file, () -> now[0])) {
                System.out.println("=== gp-62951 (25 kyu) ===");
                System.out.println();
                // a line printed in pieces is one line, with the time it was begun
                System.out.print("  at the start: ");
                now[0] = now[0].plusSeconds(75);
                System.out.println("nothing missing");
                System.err.println("bad analysis");
                System.out.println("one\ntwo");
            }
            // closed: the console's own streams are back, and nothing more reaches the file
            assertSame(consoleOut, System.out);
            assertSame(consoleErr, System.err);
            System.out.println("after the run");
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        String n = System.lineSeparator();
        assertEquals("=== gp-62951 (25 kyu) ===" + n + n + "  at the start: nothing missing" + n + "one\ntwo" + n + "after the run" + n,
                console.toString(StandardCharsets.UTF_8));
        assertEquals("bad analysis" + n, errors.toString(StandardCharsets.UTF_8));
        assertEquals("11-23 10:45:07  === gp-62951 (25 kyu) ===" + n + n
                + "11-23 10:45:07    at the start: nothing missing" + n
                + "11-23 10:46:22  bad analysis" + n
                + "11-23 10:46:22  one\n11-23 10:46:22  two" + n, Files.readString(file.toPath()));
    }

    @Test
    void aLaterRunWithTheSameFileAddsToIt() throws Exception {
        PrintStream out = System.out, err = System.err;
        File file = tmp.resolve("run.log").toFile();
        LocalDateTime at = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            try (RunLog log = RunLog.open(file, () -> at)) {
                System.out.print("first\n");
            }
            try (RunLog log = RunLog.open(file, () -> at)) {
                System.out.print("second\n");
            }
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertEquals("01-02 03:04:05  first\n01-02 03:04:05  second\n", Files.readString(file.toPath()));
    }
}
