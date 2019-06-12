package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.List;

public abstract class AbstractPullrequest {

    public interface Revision {
        Repository getRepository();

        Branch getBranch();

        Commit getCommit();
    }

    public interface Repository {
        String getName();

        String getOwnerName();

        String getRepositoryName();
    }

    public interface Branch {
        String getName();
    }

    public interface Commit {
        String getHash();
    }

    public interface User {
        String getDisplayName();
    }

    public interface Author {
        String getDisplayName();

        String getCombinedUsername();
    }

    public interface Participant {
        String getRole() ;

        Boolean getApproved();

        User getUser();
    }

    public interface Comment extends Comparable<Comment> {
        Integer getId();

        String getContent();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Response<T> {
        private int pageLength;
        private List<T> values;
        private int page;
        private int size;
        private String next;

        @JsonProperty("pagelen")
        public int getPageLength() {
            return pageLength;
        }
        @JsonProperty("pagelen")
        public void setPageLength(int pageLength) {
            this.pageLength = pageLength;
        }
        public List<T> getValues() {
            return values;
        }
        public void setValues(List<T> values) {
            this.values = values;
        }
        public int getPage() {
            return page;
        }
        public void setPage(int page) {
            this.page = page;
        }
        public int getSize() {
            return size;
        }
        public void setSize(int size) {
            this.size = size;
        }
        public String getNext() {
            return next;
        }
        public void setNext(String next) {
            this.next = next;
        }
    }

    public abstract String getTitle();

    public abstract Revision getDestination();

    public abstract Revision getSource();

    public abstract String getState();

    public abstract String getId();

    public abstract Participant[] getParticipants();

    public abstract Author getAuthor();

    public abstract boolean getMergeConditionsSatisfied();

    public abstract void setMergeConditionsSatisfied(boolean mergeConditionsSatisfied);
}
