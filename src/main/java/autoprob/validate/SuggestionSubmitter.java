package autoprob.validate;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

// cmd=suggest: offers a finding of the missing-variations finder (finding=<its json>) to
// goproblems.com as a suggested edit of the problem, which the author or an editor approves
// or rejects. a dry run unless suggest.post=true: it prints what it would send.
//
// the site (gp2, SuggestedProblemEditsController) takes problemId, comment, originalSgf and
// suggestedSgf, and checks none of them: approving a suggestion replaces the problem's sgf
// with the suggested one, and creating one notifies the problem's author and goes out on the
// site's public feed. so the checks are made here:
// - the suggestion starts from the sgf the site has now, and adds the finding's lines to it
//   on the problem's own board and nothing else, since the site shows reviewers every node
//   that changed (SuggestionDraft);
// - the problem must not have changed since the finder looked at it (suggest.source, the
//   file it read, sample_problems/<problem>.sgf by default);
// - no suggestion is open on the problem, and none made before has the lines already,
//   approved or rejected (suggest.force=true goes on past an open one).
// the crawl (cmd=crawl, crawl-review, crawl-submit) does the same for every problem it finds
// something in, with a review in between
public class SuggestionSubmitter {
    private final Properties props;

    public SuggestionSubmitter(Properties props) {
        this.props = props;
    }

    public void run() throws Exception {
        String path = props.getProperty("finding");
        if (path == null) throw new IllegalArgumentException("finding=<a *_missing.json the finder wrote> is required");
        boolean post = Boolean.parseBoolean(props.getProperty("suggest.post", "false"));
        boolean force = Boolean.parseBoolean(props.getProperty("suggest.force", "false"));
        MissingVariationFinder.Finding f = new Gson().fromJson(Files.readString(new File(path).toPath()),
                MissingVariationFinder.Finding.class);
        if (f.moves == null || f.moves.isEmpty()) {
            System.out.println(f.problem + ": the finding has no missing move; moves that fall short are not suggested yet");
            return;
        }
        int id = Integer.parseInt(f.problem.replaceFirst("^gp-", ""));
        GoproblemsSite site = new GoproblemsSite(props);

        // the problem as the site has it now, which must be the one the finder read
        JsonObject problem = site.problem(id);
        if (problem == null) throw new IllegalStateException("the site has no problem " + id);
        String original = problem.get("sgf").getAsString();
        File source = new File(props.getProperty("suggest.source", "sample_problems/" + f.problem + ".sgf"));
        if (source.exists() && !SuggestionDraft.sameProblem(original, Files.readString(source.toPath()))) {
            System.out.println(f.problem + ": the problem on the site is not the one the finder read (" + source
                    + "); run the finder on it again");
            return;
        }

        SuggestionDraft draft = SuggestionDraft.build(id, original, List.of(f), 0);
        if (draft == null) {
            System.out.println(f.problem + ": the problem has every line already");
            return;
        }
        String blocked = draft.blockedBy(site, force);
        if (blocked != null) {
            System.out.println(f.problem + ": " + blocked + "; not suggesting it" + (blocked.contains("open") ? " (suggest.force=true goes on)" : ""));
            return;
        }
        String comment = props.getProperty("suggest.comment", draft.comment);
        System.out.println(f.problem + ": " + draft.addedNodes + " node" + (draft.addedNodes == 1 ? "" : "s") + " added");
        System.out.println("comment: " + comment);
        System.out.println("original:  " + draft.originalSgf);
        System.out.println("suggested: " + draft.suggestedSgf);
        if (!post) {
            System.out.println("dry run: suggest.post=true sends it");
            return;
        }
        JsonObject made = site.create(draft.payload(comment));
        System.out.println("suggested: #" + made.get("id").getAsString() + " on problem " + id);
    }
}
