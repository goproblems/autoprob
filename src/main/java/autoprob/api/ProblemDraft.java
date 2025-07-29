package autoprob.api;

public class ProblemDraft {
    public String sgf;
    public String genre;
    public String sourceId;
    public String category;
    public Integer rating;
    public Boolean standard;

    public ProblemDraft(String sgf, String genre, String sourceId, String category, Integer rating, Boolean standard) {
        this.sgf = sgf;
        this.genre = genre;
        this.sourceId = sourceId;
        this.category = category;
        this.rating = rating;
        this.standard = standard;
    }

}
