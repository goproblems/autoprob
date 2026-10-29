package autoprob.validate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

// a suggestion's tree as edited by hand in the crawl's review (edits/gp-<id>.json): the
// suggested sgf as edited, and the draft's own it was made from. it holds for that draft only:
// a judging or a redraft that changes the draft sets it aside with the review (Crawler.retire),
// and it is what goes to the site in place of the draft's (CrawlSubmitter)
public class ReviewEdit {
    public String base; // the draft's suggestedSgf the edits were made on
    public String sgf;  // the suggested sgf as edited
    public String by;
    public String time;

    static File file(File dir, String name) {
        return new File(dir, "edits/" + name + ".json");
    }

    // the edits for the problem's suggestion, or null
    static ReviewEdit read(File dir, String name) throws IOException {
        File f = file(dir, name);
        return f.exists() ? RunRecorder.GSON.fromJson(Files.readString(f.toPath()), ReviewEdit.class) : null;
    }

    // whether the edits were made on the draft as it is
    boolean holdsFor(SuggestionDraft d) {
        return base != null && base.equals(d.suggestedSgf);
    }

    // the draft with its edits, when it has edits made on it
    static SuggestionDraft apply(ReviewEdit e, SuggestionDraft d) {
        return e != null && e.holdsFor(d) ? d.edited(e.sgf) : d;
    }
}
