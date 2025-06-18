package autoprob.api;

import java.util.List;

public class UserProfile {
    public User user;

    public static class User {
        public int id;
        public String name;
        public String info;
        public double visibleElo;
        public double displayRating;
        public double highestVisibleElo;
        public int solveTotal;
        public int dailyProblemsSolved;
        public int problemsCount;
        public int friendsCount;
        public int commentsCount;
        public Avatar avatar;
        public int rushBestScore;
        public int achievementsCount;
        public int timeTrialLevelsWonCount;
        public String lastHere;
        public String joined;
        public List<String> roles;
        public int gobits;
        public boolean isPro;
        public String language;
        public int notificationsCount;
        public Rank rank;
    }

    public static class Avatar {
        public boolean current;
        public List<Source> sources;
        public Object id; // Can be null
    }

    public static class Source {
        public String url;
        public int size;
    }

    public static class Rank {
        public int value;
        public String unit;
        public boolean exact;

        @Override
        public String toString() {
            return value + " " + unit;
        }
    }
}