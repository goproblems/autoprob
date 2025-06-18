package autoprob.api;

import java.util.List;

/**
 * Represents a Go problem from the API
 */
public class Problem {
    public int id;
    public String description;
    public String imageUrl;
    public UserProfile.Rank rank;
    public String specificGenre;
    public int correctAnswers;
    public int wrongAnswers;
    public List<String> tags;
    public String createdAt;
    public String updatedAt;
    public Author author;
    public String sgf;
    
    public static class Author {
        public int id;
        public String name;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Problem #").append(id).append("\n");
        sb.append("Rank: ").append(rank.value).append(rank.unit).append("\n");
        sb.append("Genre: ").append(specificGenre).append("\n");
        
        if (author != null) {
            sb.append("Author: ").append(author.name).append("\n");
        }
        
        sb.append("Created: ").append(createdAt).append("\n");
        sb.append("Success rate: ").append(correctAnswers).append(" correct / ")
          .append(wrongAnswers).append(" wrong\n");
        
        if (description != null && !description.isEmpty()) {
            sb.append("\nDescription:\n").append(description).append("\n");
        }
        
        if (tags != null && !tags.isEmpty()) {
            sb.append("\nTags: ");
            for (int i = 0; i < tags.size(); i++) {
                sb.append(tags.get(i));
                if (i < tags.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append("\n");
        }
        sb.append("\nSGF: " + sgf.substring(0, Math.min(50, sgf.length())) + "...");

        
        return sb.toString();
    }
}