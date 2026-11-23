package autoprob.validate;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

// a run's console output, kept in a file as well: from open to close everything printed to
// System.out and System.err goes to the file too, each line with the time it was printed. the
// console alone is gone with its terminal: the crawl of 2026-11-22 ran 26 hours, and what it
// had made of the problem it was stopped on, five hours in, could not be read anywhere. a file
// that cannot be written does not stop the run, which says so once and goes on without it
final class RunLog implements Closeable {
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final PrintStream out, err; // the console's own, put back on close
    private final Stamped file;

    private RunLog(PrintStream out, PrintStream err, Stamped file) {
        this.out = out;
        this.err = err;
        this.file = file;
    }

    // the file of a run that starts now, in the run's folder: logs/20261123-104500.log
    static File file(File dir, LocalDateTime start) {
        return new File(dir, "logs/" + NAME.format(start) + ".log");
    }

    static RunLog open(File file) throws IOException {
        return open(file, LocalDateTime::now);
    }

    // clock: the time for the stamps, a test's own
    static RunLog open(File file, Supplier<LocalDateTime> clock) throws IOException {
        Files.createDirectories(file.getAbsoluteFile().getParentFile().toPath());
        PrintStream out = System.out, err = System.err;
        Stamped stamped = new Stamped(new BufferedOutputStream(new FileOutputStream(file, true)), clock, file, out);
        // each takes the console's own encoding, so the console gets the bytes it got before
        System.setOut(new PrintStream(new Tee(out, stamped), true, out.charset()));
        System.setErr(new PrintStream(new Tee(err, stamped), true, err.charset()));
        return new RunLog(out, err, stamped);
    }

    @Override
    public void close() {
        System.out.flush();
        System.err.flush();
        System.setOut(out);
        System.setErr(err);
        file.close();
    }

    // what is written goes to the console, then to the file
    private static final class Tee extends OutputStream {
        private final PrintStream console;
        private final Stamped file;

        Tee(PrintStream console, Stamped file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public void write(int b) {
            console.write(b);
            file.write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            console.write(b, off, len);
            file.write(b, off, len);
        }

        @Override
        public void flush() {
            console.flush();
            file.flush();
        }
    }

    // the file's side: each line starts with the time it was begun, an empty line with nothing.
    // both streams write here, so a line of one can be broken by the other, and the stamps then
    // mark the pieces
    static final class Stamped {
        private final OutputStream to;
        private final Supplier<LocalDateTime> clock;
        private final File file;
        private final PrintStream console; // the console's own, to say that the file failed
        private boolean lineStart = true;
        private boolean failed;

        Stamped(OutputStream to, Supplier<LocalDateTime> clock, File file, PrintStream console) {
            this.to = to;
            this.clock = clock;
            this.file = file;
            this.console = console;
        }

        synchronized void write(byte[] b, int off, int len) {
            if (failed) return;
            try {
                int from = off;
                for (int i = off; i < off + len; i++) {
                    if (lineStart && b[i] != '\n') {
                        if (i > from) to.write(b, from, i - from);
                        to.write((STAMP.format(clock.get()) + "  ").getBytes(StandardCharsets.US_ASCII));
                        from = i;
                    }
                    lineStart = b[i] == '\n';
                }
                if (off + len > from) to.write(b, from, off + len - from);
            } catch (IOException e) {
                fail(e);
            }
        }

        synchronized void flush() {
            if (failed) return;
            try {
                to.flush();
            } catch (IOException e) {
                fail(e);
            }
        }

        synchronized void close() {
            try {
                to.close();
            } catch (IOException e) {
                if (!failed) fail(e);
            }
        }

        private void fail(IOException e) {
            failed = true;
            console.println("the run's log " + file + " cannot be written (" + e.getMessage() + "): the run goes on without it");
        }
    }
}
