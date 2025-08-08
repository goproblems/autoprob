package autoprob.api;

/**
 * Represents an individual user attempt at solving a Go problem
 */
public class Attempt {
    public int id;
    public int problemId;
    public UserProfile.Rank rank;
    public boolean solved;
    public boolean hardstop;
    public String path; // move sequence
    public String createdAt;
    public String context;
    public String contextRepresentation;
    public Integer time; // time taken in milliseconds
    public User user;
    
    public static class User {
        public int id;
        public String name;
        public Avatar avatar;
        public String lastOnlineAt;
        public UserProfile.Rank rank;
        public Double elo;
    }
    
    public static class Avatar {
        public boolean current;
        public Source[] sources;
        public Integer id;
    }
    
    public static class Source {
        public String url;
        public int size;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Attempt #").append(id);
        
        if (user != null && user.name != null && !user.name.isEmpty()) {
            sb.append(" by ").append(user.name);
        } else if (user != null) {
            sb.append(" by user ").append(user.id);
        }
        
        sb.append(" - ").append(solved ? "SUCCESS" : "FAILED");
        
        if (time != null) {
            sb.append(" in ").append(time / 1000.0).append("s");
        }
        
        if (createdAt != null) {
            sb.append(" at ").append(createdAt);
        }
        
        if (context != null && !context.equals(".")) {
            sb.append(" (").append(contextRepresentation).append(")");
        }
        
        if (path != null && !path.isEmpty()) {
            sb.append("\n  Moves: ").append(path);
        }
        
        if (user != null && user.rank != null) {
            sb.append("\n  User rank: ").append(user.rank.value).append(user.rank.unit);
            if (user.elo != null) {
                sb.append(", Elo: ").append(String.format("%.1f", user.elo));
            }
        }
        
        return sb.toString();
    }
}