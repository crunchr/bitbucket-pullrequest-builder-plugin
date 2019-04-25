package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.instanceOf;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;

import org.apache.commons.lang.StringUtils;

import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.AbstractPullrequest;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.ApiClient;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BuildState;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.cloud.CloudApiClient;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.cloud.CloudBitbucketCause;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.server.ServerApiClient;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.server.ServerBitbucketCause;
import hudson.model.Item;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.SCMSourceOwners;

/**
 * Created by nishio
 */
public class BitbucketRepository {
    private static final Logger logger = Logger.getLogger(BitbucketRepository.class.getName());
    private static final String BUILD_DESCRIPTION = "%s: %s into %s";
    private static final String BUILD_REQUEST_DONE_MARKER = "ttp build flag";
    private static final String BUILD_REQUEST_MARKER_TAG_SINGLE_RX = "\\#[\\w\\-\\d]+";
    private static final String BUILD_REQUEST_MARKER_TAGS_RX = "\\[bid\\:\\s?(.*)\\]";
    /**
     * Default value for comment trigger.
     */
    public static final String DEFAULT_COMMENT_TRIGGER = "test this please";

    private BitbucketPullRequestsBuilder builder;
    private BitbucketBuildTrigger trigger;
    private ApiClient client;
    private List<String> approvedPullRequests;

    public BitbucketRepository(String projectPath, BitbucketPullRequestsBuilder builder) {
        this.builder = builder;
        approvedPullRequests = new ArrayList<String>();
    }

    public void init() {
        this.init(null, null);
    }

    public <T extends ApiClient.HttpClientFactory> void init(T httpFactory) {
        this.init(null, httpFactory);
    }

    public void init(ApiClient client) {
        this.init(client, null);
    }

    public <T extends ApiClient.HttpClientFactory> void init(ApiClient client, T httpFactory) {
        this.trigger = this.builder.getTrigger();

        if (client == null) {
            String username = trigger.getUsername();
            String password = trigger.getPassword();
            StandardUsernamePasswordCredentials credentials = getCredentials(trigger.getCredentialsId());
            if (credentials != null) {
                username = credentials.getUsername();
                password = credentials.getPassword().getPlainText();
            }

            if (this.trigger.isCloud()) {
                this.client = createCloudClient(httpFactory, username, password);
            } else {
                this.client = createServerClient(httpFactory, username, password);
            }
        } else this.client = client;
    }

    private <T extends ApiClient.HttpClientFactory> CloudApiClient createCloudClient(T httpFactory, String username, String password) {
        return new CloudApiClient(
            username,
            password,
            trigger.getRepositoryOwner(),
            trigger.getRepositoryName(),
            trigger.getCiKey(),
            trigger.getCiName(),
            httpFactory
        );
    }

    private <T extends ApiClient.HttpClientFactory> ServerApiClient createServerClient(T httpFactory, String username, String password) {
        return new ServerApiClient(
            trigger.getBitbucketServer(),
            username,
            password,
            trigger.getRepositoryOwner(),
            trigger.getRepositoryName(),
            trigger.getCiKey(),
            trigger.getCiName(),
            httpFactory);
    }

    public <T extends AbstractPullrequest> List<T> getTargetPullRequests() {
        logger.fine("Fetch PullRequests.");
        List<T> pullRequests = client.getPullRequests();
        List<T> targetPullRequests = new ArrayList<>();
        List<String> activeApprovedPullRequests = new ArrayList<String>();

        for(T pullRequest : pullRequests) {
            if (isBuildTarget(pullRequest)) {
                targetPullRequests.add(pullRequest);
            }

            if(approvedPullRequests.contains(pullRequest.getId())) {
                activeApprovedPullRequests.add(pullRequest.getId());
            }
        }

        logger.info("Approved pull request changed to: " + StringUtils.join(approvedPullRequests, ", "));

        approvedPullRequests = activeApprovedPullRequests;

        return targetPullRequests;
    }

    public ApiClient getClient() {
      return this.client;
    }

    public void addFutureBuildTasks(Collection<AbstractPullrequest> pullRequests) {
        for(AbstractPullrequest pullRequest : pullRequests) {
            if ( this.trigger.getApproveIfSuccess() ) {
                deletePullRequestApproval(pullRequest.getId());
            }

            final BitbucketCause cause = createCause(pullRequest);

            setBuildStatus(cause, BuildState.INPROGRESS, getInstance().getRootUrl());
            this.builder.getTrigger().startJob(cause);
        }
    }

    private BitbucketCause createCause(AbstractPullrequest pullRequest) {
        // pullRequest.getDestination().getCommit() may return null for pull requests with merge conflicts
        // * see: https://github.com/nishio-dens/bitbucket-pullrequest-builder-plugin/issues/119
        // * see: https://github.com/nishio-dens/bitbucket-pullrequest-builder-plugin/issues/98
        final String destinationCommitHash;
        if (pullRequest.getDestination().getCommit() == null) {
            logger.log(Level.INFO, "Pull request #{0} ''{1}'' in repo ''{2}'' has a null value for destination commit.",
                    new Object[]{pullRequest.getId(), pullRequest.getTitle(), pullRequest.getDestination().getRepository().getRepositoryName()});
            destinationCommitHash = null;
        } else {
            destinationCommitHash = pullRequest.getDestination().getCommit().getHash();
        }

        final BitbucketCause cause;
        if (this.trigger.isCloud()) {
            cause = new CloudBitbucketCause(
                pullRequest.getSource().getBranch().getName(),
                pullRequest.getDestination().getBranch().getName(),
                pullRequest.getSource().getRepository().getOwnerName(),
                pullRequest.getSource().getRepository().getRepositoryName(),
                pullRequest.getId(),
                pullRequest.getDestination().getRepository().getOwnerName(),
                pullRequest.getDestination().getRepository().getRepositoryName(),
                pullRequest.getTitle(),
                pullRequest.getSource().getCommit().getHash(),
                destinationCommitHash,
                pullRequest.getAuthor().getCombinedUsername(),
                pullRequest.getMergeConditionsSatisfied()
            );
        } else {
            cause = new ServerBitbucketCause(
                trigger.getBitbucketServer(),
                pullRequest.getSource().getBranch().getName(),
                pullRequest.getDestination().getBranch().getName(),
                pullRequest.getSource().getRepository().getOwnerName(),
                pullRequest.getSource().getRepository().getRepositoryName(),
                pullRequest.getId(),
                pullRequest.getDestination().getRepository().getOwnerName(),
                pullRequest.getDestination().getRepository().getRepositoryName(),
                pullRequest.getTitle(),
                pullRequest.getSource().getCommit().getHash(),
                pullRequest.getDestination().getCommit().getHash(),
                pullRequest.getAuthor().getCombinedUsername(),
                pullRequest.getMergeConditionsSatisfied()
            );
        }
        return cause;
    }

    private Jenkins getInstance() {
        final Jenkins instance = Jenkins.getInstance();
        if (instance == null){
            throw new IllegalStateException("Jenkins instance is NULL!");
        }
        return instance;
    }


    public void setBuildStatus(BitbucketCause cause, BuildState state, String buildUrl) {
        String comment = null;
        String sourceCommit = cause.getSourceCommitHash();
        String owner = cause.getRepositoryOwner();
        String repository = cause.getRepositoryName();
        String destinationBranch = cause.getTargetBranch();

        logger.fine("setBuildStatus " + state + " for commit: " + sourceCommit + " with url " + buildUrl);

        if (state == BuildState.FAILED || state == BuildState.SUCCESSFUL) {
            comment = String.format(BUILD_DESCRIPTION, builder.getJob().getDisplayName(), sourceCommit, destinationBranch);
        }

        this.client.setBuildStatus(owner, repository, sourceCommit, state, buildUrl, comment, this.builder.getProjectId());
    }

    public void deletePullRequestApproval(String pullRequestId) {
        this.client.deletePullRequestApproval(pullRequestId);
    }

    public void postPullRequestApproval(String pullRequestId) {
        this.client.postPullRequestApproval(pullRequestId);
    }

    public String getMyBuildTag(String buildKey) {
      return "#" + this.client.buildStatusKey(buildKey);
    }

    final static Pattern BUILD_TAGS_RX = Pattern.compile(BUILD_REQUEST_MARKER_TAGS_RX, Pattern.CASE_INSENSITIVE | Pattern.CANON_EQ);
    final static Pattern SINGLE_BUILD_TAG_RX = Pattern.compile(BUILD_REQUEST_MARKER_TAG_SINGLE_RX, Pattern.CASE_INSENSITIVE | Pattern.CANON_EQ);
    final static String CONTENT_PART_TEMPLATE = "```[bid: %s]```";

    private List<String> getAvailableBuildTagsFromTTPComment(String buildTags) {
      logger.log(Level.FINE, "Parse {0}", new Object[]{ buildTags });
      List<String> availableBuildTags = new LinkedList<String>();
      Matcher subBuildTagMatcher = SINGLE_BUILD_TAG_RX.matcher(buildTags);
      while(subBuildTagMatcher.find()) availableBuildTags.add(subBuildTagMatcher.group(0).trim());
      return availableBuildTags;
    }

    public boolean hasMyBuildTagInTTPComment(String content, String buildKey) {
      Matcher tagsMatcher = BUILD_TAGS_RX.matcher(content);
      if (tagsMatcher.find()) {
        logger.log(Level.FINE, "Content {0} g[1]:{1} mykey:{2}", new Object[] { content, tagsMatcher.group(1).trim(), this.getMyBuildTag(buildKey) });
        return this.getAvailableBuildTagsFromTTPComment(tagsMatcher.group(1).trim()).contains(this.getMyBuildTag(buildKey));
      }
      else return false;
    }

    private void postBuildTagInTTPComment(String pullRequestId, String content, String buildKey) {
      logger.log(Level.FINE, "Update build tag for {0} build key", buildKey);
      List<String> builds = this.getAvailableBuildTagsFromTTPComment(content);
      builds.add(this.getMyBuildTag(buildKey));
      content += " " + String.format(CONTENT_PART_TEMPLATE, StringUtils.join(builds, " "));
      logger.log(Level.FINE, "Post comment: {0} with original content {1}", new Object[]{ content, this.client.postPullRequestComment(pullRequestId, content).getId() });
    }

    private boolean isTTPComment(String content) {
        // special case: in unit tests, trigger is null and can't be mocked
        String commentTrigger = DEFAULT_COMMENT_TRIGGER;
        if(trigger != null && StringUtils.isNotBlank(trigger.getCommentTrigger())) {
            commentTrigger = trigger.getCommentTrigger();
        }
      return content.contains(commentTrigger);
    }

    private boolean isTTPCommentBuildTags(String content) {
      return content.toLowerCase().contains(BUILD_REQUEST_DONE_MARKER.toLowerCase());
    }

    public List<AbstractPullrequest.Comment> filterPullRequestComments(List<AbstractPullrequest.Comment> comments) {
      logger.fine("Filter PullRequest Comments.");
      Collections.sort(comments);
      Collections.reverse(comments);
      List<AbstractPullrequest.Comment> filteredComments = new LinkedList<AbstractPullrequest.Comment>();
      for(AbstractPullrequest.Comment comment : comments) {
        String content = comment.getContent();
        logger.log(Level.FINE, "Found comment: id:" + comment.getId() +" <" + comment.getContent() + ">");
        if (content == null || content.isEmpty()) continue;
        boolean isTTP = this.isTTPComment(content);
        boolean isTTPBuild = this.isTTPCommentBuildTags(content);
        logger.log(Level.FINE, "isTTP: " + isTTP + " isTTPBuild: " + isTTPBuild);
        if (isTTP || isTTPBuild)  filteredComments.add(comment);
        if (isTTP) break;
      }
      return filteredComments;
    }

    private boolean isBuildTarget(AbstractPullrequest pullRequest) {
        if (pullRequest.getState() != null && pullRequest.getState().equals("OPEN")) {
            if (isSkipBuild(pullRequest.getTitle()) || !isFilteredBuild(pullRequest)) {
                logger.log(Level.FINE, "Skipping build for " + pullRequest.getTitle() + 
                        ": skip:" + isSkipBuild(pullRequest.getTitle()) + " : isFilteredBuild: " + 
                        isFilteredBuild(pullRequest));
                return false;
            }

            AbstractPullrequest.Revision source = pullRequest.getSource();
            String sourceCommit = source.getCommit().getHash();
            AbstractPullrequest.Revision destination = pullRequest.getDestination();
            String owner = destination.getRepository().getOwnerName();
            String repositoryName = destination.getRepository().getRepositoryName();

            AbstractPullrequest.Repository sourceRepository = source.getRepository();
            String buildKeyPart = this.builder.getProjectId();

            final boolean commitAlreadyBeenProcessed = this.client.hasBuildStatus(
              sourceRepository.getOwnerName(), sourceRepository.getRepositoryName(), sourceCommit, buildKeyPart
            );
            if (commitAlreadyBeenProcessed) logger.log(Level.FINE,
              "Commit {0}#{1} has already been processed",
              new Object[]{ sourceCommit, buildKeyPart }
            );

            final String id = pullRequest.getId();
            List<AbstractPullrequest.Comment> comments = client.getPullRequestComments(owner, repositoryName, id);

            boolean rebuildCommentAvailable = false;
            if (comments != null) {
                Collection<AbstractPullrequest.Comment> filteredComments = this.filterPullRequestComments(comments);
                boolean hasMyBuildTag = false;
                for (AbstractPullrequest.Comment comment : filteredComments) {
                    String content = comment.getContent();
                    if (this.isTTPComment(content)) {
                        rebuildCommentAvailable = true;
                        logger.log(Level.FINE,
                          "Rebuild comment available for commit {0} and comment #{1}",
                          new Object[]{ sourceCommit, comment.getId() }
                        );
                    }
                    if (isTTPCommentBuildTags(content))
                        hasMyBuildTag |= this.hasMyBuildTagInTTPComment(content, buildKeyPart);
                }
                rebuildCommentAvailable &= !hasMyBuildTag;
            }
            if (rebuildCommentAvailable) this.postBuildTagInTTPComment(id, "TTP build flag", buildKeyPart);

            boolean canBuildTarget = rebuildCommentAvailable || !commitAlreadyBeenProcessed;
            canBuildTarget |= trigger.getCheckTriggerConditions() && triggerConditionsSatisfied(pullRequest);
            logger.log(Level.FINE, "Build target? {0} [rebuild:{1} processed:{2}]", new Object[]{ canBuildTarget, rebuildCommentAvailable, commitAlreadyBeenProcessed});
            return canBuildTarget;
        }

        return false;
    }

    private boolean triggerConditionsSatisfied(AbstractPullrequest pullRequest) {
        boolean result = false;
        List<String> logLines = new ArrayList<String>();
        Map<String, Boolean> requiredConditions = new HashMap<String, Boolean>();

        // Only check if pull requests has not been aproved yet
        if(!approvedPullRequests.contains(pullRequest.getId())) {
            requiredConditions.put("AuthorApproved", trigger.getRequireAuthorApproval());
            requiredConditions.put("RequiredUsersApproved", trigger.getRequireUserApprovals());
            requiredConditions.put("MinNumApprovals", trigger.getRequireMinNumApprovals());
            requiredConditions.put("AllParticipantsApproved", trigger.getRequireAllParticipants());

            // Check all conditions in the requiredConditions map
            boolean success = false;
            boolean conditions_success = true;
            for (Map.Entry<String, Boolean> entry: requiredConditions.entrySet()) {
                // Skip if not required
                if (!entry.getValue()) {
                    logger.fine("Skipped condition: " + entry.getKey());
                    continue;
                }

                switch (entry.getKey()) {
                case "AuthorApproved":
                    success = hasAuthorApproved(pullRequest);
                    logLines.add("author (" + pullRequest.getAuthor().getUsername() + ") has " + (success ? "" : "NOT ") + "approved");
                    break;
                case "RequiredUsersApproved":
                    success = haveRequiredParticipantsApproved(pullRequest);
                    logLines.add((success ? "" : "NOT ") + "all required participants have approved");
                    break;
                case "MinNumApprovals":
                    success = hasEnoughApprovals(pullRequest);
                    logLines.add((success ? "" : "NOT ") + "enough approvals");
                    break;
                case "AllParticipantsApproved":
                    success = haveAllReviewersApproved(pullRequest);
                    logLines.add((success ? "" : "NOT ") + "all participants have approved");
                    break;
                default:
                    logger.warning("Unknown approval condition");
                }

                conditions_success &= success;
                logger.fine("Checked condition: " + entry.getKey() + ", success: " + success + ", conditions succes: " + conditions_success);
            }

            // Do not trigger build if no conditions were checked
            if(!requiredConditions.values().contains(true)) {
              // Make sure build is not triggered again
              approvedPullRequests.add(pullRequest.getId());

              logger.info("Approved pull requests: " + StringUtils.join(approvedPullRequests, ", "));

              return true;
            } else {
                result = !conditions_success;
            }
        }

        logger.info("Trigger conditions were NOT satisfied for pull request " + pullRequest.getId());
        logger.info("Trigger conditions info: " + StringUtils.join(logLines, ", "));
        return result;
    }

    private boolean hasAuthorApproved(AbstractPullrequest pullRequest) {
        final String authorUsername = pullRequest.getAuthor().getUsername();

        // find author in list of participants
        for(AbstractPullrequest.Participant participant : pullRequest.getParticipants()) {
            if(participant.getUser().getUsername().equals(authorUsername)) {
                return participant.getApproved();
            }
        }

        logger.warning("Could not find author " + authorUsername + " in the list of pull request participants");
        return false;
    }

    private boolean haveRequiredParticipantsApproved(AbstractPullrequest pullRequest) {
        // Early exit if there are no required participants
        if(trigger.getRequiredUsers().equals("")) { return true; }

        String[] requiredParticipants = trigger.getRequiredUsers().split("[\\s]*,[\\s]*");

        for(String requiredParticipant : requiredParticipants) {
            boolean foundParticipant = false;

            for(AbstractPullrequest.Participant participant : pullRequest.getParticipants()) {
                if(participant.getUser().getUsername().equals(requiredParticipant)) {
                    foundParticipant = true;

                    if(!participant.getApproved()) {
                        return false;
                    }

                    continue;
                }
            }

            if(!foundParticipant) {
                logger.warning("Did not find required pull request participant " + requiredParticipant);
                return false;
            }
        }

        return true;
    }

    private boolean hasEnoughApprovals(AbstractPullrequest pullRequest) {
        int numApprovals = 0;
        int neededApprovals;

        try {
            neededApprovals = Integer.parseInt(trigger.getMinNumApprovals());
        } catch(NumberFormatException e) {
            logger.warning("Could not parse minimum number of required approvals: " + trigger.getMinNumApprovals());
            return false;
        }

        for(AbstractPullrequest.Participant participant : pullRequest.getParticipants()) {
            if(participant.getApproved()) {
                numApprovals++;
            }
        }

        logger.info("Number of approvals: " + numApprovals + " (needed: " + neededApprovals + ")");

        return numApprovals >= neededApprovals;
    }

    private boolean haveAllReviewersApproved(AbstractPullrequest pullRequest) {
        String ignoredUsersString = trigger.getAllParticipantsIgnoredUsers();

        if(ignoredUsersString == null) {
            ignoredUsersString = "";
        }

        List<String> ignoredParticipant = Arrays.asList(ignoredUsersString.split("[\\s]*,[\\s]*"));

        for(AbstractPullrequest.Participant participant : pullRequest.getParticipants()) {
            if(participant.getRole().equals("REVIEWER") &&
               !ignoredParticipant.contains(participant.getUser().getUsername()) &&
               !participant.getApproved()) {
                return false;
            }
        }

        return true;
    }

    private boolean isSkipBuild(String pullRequestTitle) {
        String skipPhrases = this.trigger.getCiSkipPhrases();
        if (skipPhrases != null && !"".equals(skipPhrases)) {
            String[] phrases = skipPhrases.split(",");
            for(String phrase : phrases) {
                if (pullRequestTitle.toLowerCase().contains(phrase.trim().toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isFilteredBuild(AbstractPullrequest pullRequest) {

        BitbucketCause cause = createCause(pullRequest);

        //@FIXME: Way to iterate over all available SCMSources
        List<SCMSource> sources = new LinkedList<SCMSource>();
        for(SCMSourceOwner owner : SCMSourceOwners.all())
          for(SCMSource src : owner.getSCMSources())
            sources.add(src);

        BitbucketBuildFilter filter = !this.trigger.getBranchesFilterBySCMIncludes() ?
          BitbucketBuildFilter.instanceByString(this.trigger.getBranchesFilter()) :
          BitbucketBuildFilter.instanceBySCM(sources, this.trigger.getBranchesFilter());

        return filter.approved(cause);
    }

    private StandardUsernamePasswordCredentials getCredentials(String credentialsId) {
        if (null == credentialsId) return null;
        return CredentialsMatchers
            .firstOrNull(
                CredentialsProvider.lookupCredentials(
                    StandardUsernamePasswordCredentials.class,
                    (Item) null,
                    ACL.SYSTEM,
                    (DomainRequirement) null
                ),
                CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId), instanceOf(UsernamePasswordCredentials.class))
            );
    }
}
