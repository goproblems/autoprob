package autoprob.api;

import java.util.List;

/**
 * Represents a paginated response containing multiple attempts
 */
public class AttemptListResponse {
    public List<Attempt> items; // API uses "items" not "attempts"
    public int totalRecords; // API uses "totalRecords" not "totalCount"
    public int limit;
    public int offset;
    public boolean hasMore;
    public String nextUrl;
    public String prevUrl;
    
    // Convenience getter to match our expected interface
    public List<Attempt> getAttempts() {
        return items;
    }
    
    public int getTotalCount() {
        return totalRecords;
    }

    public AttemptListResponse() {
    }

    public AttemptListResponse(List<Attempt> items, int totalRecords, int limit, int offset) {
        this.items = items;
        this.totalRecords = totalRecords;
        this.limit = limit;
        this.offset = offset;
        this.hasMore = (offset + items.size()) < totalRecords;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Recent Attempts (").append(items != null ? items.size() : 0);
        sb.append(" of ").append(totalRecords).append(" total) ===\n");
        
        if (items != null && !items.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                sb.append(i + 1).append(". ").append(items.get(i).toString()).append("\n");
            }
        } else {
            sb.append("No attempts found.\n");
        }
        
        if (hasMore) {
            sb.append("... and ").append(totalRecords - offset - (items != null ? items.size() : 0))
              .append(" more attempts");
        }
        
        return sb.toString();
    }
}