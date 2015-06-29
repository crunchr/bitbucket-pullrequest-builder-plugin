package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestResponseValueUser;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketPullRequestResponseValueParticipant {
    private String role;
    private BitbucketPullRequestResponseValueUser user;
    private Boolean approved;

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

    public Boolean getApproved() {
      return approved;
    }

    public void setApproved(Boolean approved) {
      this.approved = approved;
    }
}
