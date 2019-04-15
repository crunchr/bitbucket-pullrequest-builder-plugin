package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.cloud;

import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.BitbucketCause;

/**
 * Created by nishio
 */
public class CloudBitbucketCause extends BitbucketCause {

    public static final String BITBUCKET_URL = "https://bitbucket.org/";

    public CloudBitbucketCause(String sourceBranch,
                               String targetBranch,
                               String repositoryOwner,
                               String repositoryName,
                               String pullRequestId,
                               String destinationRepositoryOwner,
                               String destinationRepositoryName,
                               String pullRequestTitle,
                               String sourceCommitHash,
                               String destinationCommitHash,
                               String pullRequestAuthor,
                               boolean mergeConditionsSatisfied) {
        super(sourceBranch,
              targetBranch,
              repositoryOwner,
              repositoryName,
              pullRequestId,
              destinationRepositoryOwner,
              destinationRepositoryName,
              pullRequestTitle,
              sourceCommitHash,
              destinationCommitHash,
              pullRequestAuthor,
              mergeConditionsSatisfied);
    }

    @Override
    public String getShortDescription() {
        String description = "<a href=\"" + BITBUCKET_URL + this.getDestinationRepositoryOwner() + "/";
        description += this.getDestinationRepositoryName() + "/pull-request/" + this.getPullRequestId();
        description += "\">#" + this.getPullRequestId() + " " + this.getPullRequestTitle() + "</a>";
        return description;
    }

}
