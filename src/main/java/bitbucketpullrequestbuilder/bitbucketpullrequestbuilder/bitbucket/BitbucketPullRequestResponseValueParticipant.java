package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestResponseValueUser;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketPullRequestResponseValueParticipant {
    private String role;
    private BitbucketPullRequestResponseValueUser user;
    private boolean approved;

    public String getRole() {
      return role;
    }

    public void setRole(String role) {
      this.role = role;
    }

    public BitbucketPullRequestResponseValueUser getUser() {
      return user;
    }

    public void setUser(BitbucketPullRequestResponseValueUser user) {
      this.user = user;
    }

    public boolean getApproved() {
      return approved;
    }

    public void setApproved(boolean approved) {
      this.approved = approved;
    }
}
