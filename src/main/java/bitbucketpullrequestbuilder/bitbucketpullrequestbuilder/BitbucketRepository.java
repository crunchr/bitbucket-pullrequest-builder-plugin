package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketApiClient;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestComment;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestResponseValue;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestResponseValueParticipant;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestResponseValueUser;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestResponseValueRepository;

/**
 * Created by nishio
 */
public class BitbucketRepository {
    private static final Logger logger = Logger.getLogger(BitbucketRepository.class.getName());
    public static final String BUILD_START_MARKER = "[*BuildStarted* **%s**] %s into %s";
    public static final String BUILD_FINISH_MARKER = "[*BuildFinished* **%s**] %s into %s";

    public static final String BUILD_START_REGEX = "\\[\\*BuildStarted\\* \\*\\*%s\\*\\*\\] ([0-9a-fA-F]+) into ([0-9a-fA-F]+)";
    public static final String BUILD_FINISH_REGEX = "\\[\\*BuildFinished\\* \\*\\*%s\\*\\*\\] ([0-9a-fA-F]+) into ([0-9a-fA-F]+)";

    public static final String BUILD_FINISH_SENTENCE = BUILD_FINISH_MARKER + " \n\n **%s** - %s";
    public static final String BUILD_REQUEST_MARKER = "test this please";

    public static final String BUILD_SUCCESS_COMMENT =  ":white_check_mark: SUCCESS";
    public static final String BUILD_FAILURE_COMMENT = ":x: FAILURE";
    private String projectPath;
    private BitbucketPullRequestsBuilder builder;
    private BitbucketBuildTrigger trigger;
    private BitbucketApiClient client;
    private List<String> approvedPullRequests;   // The ids of pull requests that have been approved (i.e. that met the trigger conditions)

    public BitbucketRepository(String projectPath, BitbucketPullRequestsBuilder builder) {
        this.projectPath = projectPath;
        this.builder = builder;
        approvedPullRequests = new ArrayList<String>();
    }

    public void init() {
        trigger = this.builder.getTrigger();
        client = new BitbucketApiClient(
                trigger.getUsername(),
                trigger.getPassword(),
                trigger.getRepositoryOwner(),
                trigger.getRepositoryName());
    }

    public Collection<BitbucketPullRequestResponseValue> getTargetPullRequests() {
        logger.info("Fetch PullRequests.");
        List<BitbucketPullRequestResponseValue> pullRequests = client.getPullRequests();
        List<BitbucketPullRequestResponseValue> targetPullRequests = new ArrayList<BitbucketPullRequestResponseValue>();
        List<String> activeApprovedPullRequests = new ArrayList<String>();
        for(BitbucketPullRequestResponseValue pullRequest : pullRequests) {
            if (isBuildTarget(pullRequest)) {
                targetPullRequests.add(pullRequest);
            }

            if(approvedPullRequests.contains(pullRequest.getId())) {
                activeApprovedPullRequests.add(pullRequest.getId());
            }
        }

        String currentPullRequests = "";

        for(String pr : approvedPullRequests) {
            currentPullRequests += pr + ", ";
        }

        logger.info("Approved pull request changed to: " + currentPullRequests);

        approvedPullRequests = activeApprovedPullRequests;

        return targetPullRequests;
    }

    public String postBuildStartCommentTo(BitbucketPullRequestResponseValue pullRequest) {
            String sourceCommit = pullRequest.getSource().getCommit().getHash();
            String destinationCommit = pullRequest.getDestination().getCommit().getHash();
            String comment = String.format(BUILD_START_MARKER, builder.getProject().getDisplayName(), sourceCommit, destinationCommit);
            BitbucketPullRequestComment commentResponse = this.client.postPullRequestComment(pullRequest.getId(), comment);
            return commentResponse.getCommentId().toString();
    }

    public void addFutureBuildTasks(Collection<BitbucketPullRequestResponseValue> pullRequests) {
        for(BitbucketPullRequestResponseValue pullRequest : pullRequests) {
            String commentId = postBuildStartCommentTo(pullRequest);
            if ( this.trigger.getApproveIfSuccess() ) {
                deletePullRequestApproval(pullRequest.getId());
            }
            BitbucketCause cause = new BitbucketCause(
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
                    commentId,
                    pullRequest.getSourceBranchHasChanged(),
                    pullRequest.getTargetBranchHasChanged(),
                    pullRequest.getApprovalConditionsSatisfied());
            this.builder.getTrigger().startJob(cause);
        }
    }

    public void deletePullRequestComment(String pullRequestId, String commentId) {
        this.client.deletePullRequestComment(pullRequestId,commentId);
    }

    public void postFinishedComment(String pullRequestId, String sourceCommit,  String destinationCommit, boolean success, String buildUrl, String buildDescription) {
        String message = BUILD_FAILURE_COMMENT;
        if (success) {
            message = BUILD_SUCCESS_COMMENT;
        }
        String comment = String.format(BUILD_FINISH_SENTENCE, builder.getProject().getDisplayName(), sourceCommit, destinationCommit, message, buildUrl);

        if(this.trigger.getAddBuildDescriptionToComment() && buildDescription != null && !buildDescription.isEmpty()) {
            comment += "\n\n" + buildDescription;
        }

        this.client.postPullRequestComment(pullRequestId, comment);
    }

    public void deletePullRequestApproval(String pullRequestId) {
        this.client.deletePullRequestApproval(pullRequestId);
    }

    public void postPullRequestApproval(String pullRequestId) {
        this.client.postPullRequestApproval(pullRequestId);
    }

    private boolean isBuildTarget(BitbucketPullRequestResponseValue pullRequest) {
        boolean shouldBuild = true;
        boolean sourceBranchHasChanged = true;
        boolean targetBranchHasChanged = false;

        if (pullRequest.getState() != null && pullRequest.getState().equals("OPEN")) {

            if (isSkipBuild(pullRequest.getTitle())) {
                return false;
            }

            String sourceCommit = pullRequest.getSource().getCommit().getHash();

            BitbucketPullRequestResponseValueRepository destination = pullRequest.getDestination();
            String owner = destination.getRepository().getOwnerName();
            String repositoryName = destination.getRepository().getRepositoryName();
            String destinationCommit = destination.getCommit().getHash();

            String id = pullRequest.getId();
            List<BitbucketPullRequestComment> comments = client.getPullRequestComments(owner, repositoryName, id);

            if (comments != null) {
                Collections.sort(comments);
                Collections.reverse(comments);
                for (BitbucketPullRequestComment comment : comments) {
                    String content = comment.getContent();
                    if (content == null || content.isEmpty()) {
                        continue;
                    }

                    //These will match any start or finish message -- need to check commits
                    String project_build_start = String.format(BUILD_START_REGEX, builder.getProject().getDisplayName());
                    String project_build_finished = String.format(BUILD_FINISH_REGEX, builder.getProject().getDisplayName());
                    Matcher startMatcher = Pattern.compile(project_build_start, Pattern.CASE_INSENSITIVE).matcher(content);
                    Matcher finishMatcher = Pattern.compile(project_build_finished, Pattern.CASE_INSENSITIVE).matcher(content);

                    if (startMatcher.find() ||
                        finishMatcher.find()) {

                        String sourceCommitMatch;
                        String destinationCommitMatch;

                        if (startMatcher.find(0)) {
                            sourceCommitMatch = startMatcher.group(1);
                            destinationCommitMatch = startMatcher.group(2);
                        } else {
                            sourceCommitMatch = finishMatcher.group(1);
                            destinationCommitMatch = finishMatcher.group(2);
                        }

                        //first check source commit -- if it doesn't match, just move on. If it does, investigate further.
                        if (sourceCommitMatch.equalsIgnoreCase(sourceCommit)) {
                            sourceBranchHasChanged = false;

                            if((!destinationCommitMatch.equalsIgnoreCase(destinationCommit))) {
                                targetBranchHasChanged = true;

                                // if we're checking destination commits, and if this doesn't match, then move on.
                                if (this.trigger.getCheckDestinationCommit()) {
                                	  continue;
                                }
                            }

                            shouldBuild = false;
                            break;
                        }
                    }

                    if (content.contains(BUILD_REQUEST_MARKER.toLowerCase())) {
                        shouldBuild = true;
                        break;
                    }
                }

                if (trigger.getCheckTriggerConditions()) {
                    boolean triggerConditionsHaveBeenSatisfied = triggerConditionsSatisfied(pullRequest);

                    pullRequest.setApprovalConditionsSatisfied(triggerConditionsHaveBeenSatisfied || approvedPullRequests.contains(pullRequest.getId()));

                    if (triggerConditionsHaveBeenSatisfied) {
                        shouldBuild = true;
                    } else if (trigger.getCheckDestinationCommit() && targetBranchHasChanged) {
                        // Do not trigger when the target branch changes while the approval
                        // conditions have not yet been satisfied
                        if(!approvedPullRequests.contains(pullRequest.getId())) {
                            logger.info("Not triggering (target branch changed, but approval conditions have not yet been satisfied");
                            shouldBuild = false;
                        } else {
                            shouldBuild = true;
                        }
                    }
                }
            }
        }

        // Export variable that indicates whether either the source branch or the destination (if enabled in UI) branch has changed
        pullRequest.setSourceBranchHasChanged(sourceBranchHasChanged);
        pullRequest.setTargetBranchHasChanged(targetBranchHasChanged);

        logger.info("Branches have changed?? Source branch: " + sourceBranchHasChanged + " Target branch: " + targetBranchHasChanged);

        return shouldBuild;
    }

    private boolean triggerConditionsSatisfied(BitbucketPullRequestResponseValue pullRequest) {
        boolean noConditionsChecked = true;
        String logString = "";

        // Only check if pull requests has not been aproved yet
        if(!approvedPullRequests.contains(pullRequest.getId())) {
            if(trigger.getRequireAuthorApproval()) {
                noConditionsChecked = false;

                if(!hasAuthorApproved(pullRequest)) {
                    logString += "Author (" + pullRequest.getAuthor().getUsername() + ") has not approved - ";
                    logger.info("Trigger conditions were NOT satisfied for pull request " + pullRequest.getId() + ". Info: " + logString);
                    return false;
                } else {
                    logString += "Author (" + pullRequest.getAuthor().getUsername() + ") has approved - ";
                }
            }

            if(trigger.getRequireUserApprovals()) {
                noConditionsChecked = false;

                if(!haveRequiredParticipantsApproved(pullRequest)) {
                    logString += "Not all required participants have approved - ";
                    logger.info("Trigger conditions were NOT satisfied for pull request " + pullRequest.getId() + ". Info: " + logString);
                    return false;
                } else {
                    logString += "All required participants have approved - ";
                }
            }

            if(trigger.getRequireMinNumApprovals()) {
                noConditionsChecked = false;

                if(!hasEnoughApprovals(pullRequest)) {
                    logString += "Not enough approvals - ";
                    logger.info("Trigger conditions were NOT satisfied for pull request " + pullRequest.getId() + ". Info: " + logString);
                    return false;
                } else {
                    logString += "Enough approvals - ";
                }
            }

            if(trigger.getRequireAllParticipants()) {
                noConditionsChecked = false;

                if(!haveAllParticipantsApproved(pullRequest)) {
                    logString += "Not all participants have approved - ";
                    logger.info("Trigger conditions were NOT satisfied for pull request " + pullRequest.getId() + ". Info: " + logString);
                    return false;
                } else {
                    logString += "All participants have approved - ";
                }
            }

            // Do not trigger build if no conditions were checked
            if(!noConditionsChecked) {
              // Make sure build is not triggered again
              approvedPullRequests.add(pullRequest.getId());

              String currentPullRequests = "";

              for(String pr : approvedPullRequests) {
                  currentPullRequests += pr + ", ";
              }

              logger.info("Trigger conditions were satisfied for pull request " + pullRequest.getId() + ". Info: " + logString);
              logger.info("Approved pull requests: " + currentPullRequests);

              return true;
            }
        }

        return false;
    }

    private boolean hasAuthorApproved(BitbucketPullRequestResponseValue pullRequest) {
        String authorUsername = pullRequest.getAuthor().getUsername();

        // find author in list of participants
        for(BitbucketPullRequestResponseValueParticipant participant : pullRequest.getParticipants()) {
            String username = participant.getUser().getUsername();

            if(username.equals(authorUsername)) {
                if(participant.getApproved()) {
                    return true;
                } else {
                  return false;
                }
            }
        }

        logger.warning("Could not find author " + authorUsername + " in the list of pull request participants");

        return false;
    }

    private boolean haveRequiredParticipantsApproved(BitbucketPullRequestResponseValue pullRequest) {
        if(trigger.getRequiredUsers().equals("")) {
            return true;
        }

        String[] requiredParticipants = trigger.getRequiredUsers().split("[\\s]*,[\\s]*");

        for(String requiredParticipant : requiredParticipants) {
            boolean foundParticipant = false;

            for(BitbucketPullRequestResponseValueParticipant participant : pullRequest.getParticipants()) {
                String username = participant.getUser().getUsername();

                if(username.equals(requiredParticipant)) {
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

    private boolean hasEnoughApprovals(BitbucketPullRequestResponseValue pullRequest) {
        int numApprovals = 0;
        int neededApprovals;

        try {
            neededApprovals = Integer.parseInt(trigger.getMinNumApprovals());
        } catch(NumberFormatException e) {
            logger.warning("Could not parse minimum number of required approvals: " + trigger.getMinNumApprovals());
            return false;
        }

        for(BitbucketPullRequestResponseValueParticipant participant : pullRequest.getParticipants()) {
            if(participant.getApproved()) {
                numApprovals++;
            }
        }

        logger.info("Number of approvals: " + numApprovals + " (needed: " + neededApprovals + ")");

        return numApprovals >= neededApprovals;
    }

    private boolean haveAllParticipantsApproved(BitbucketPullRequestResponseValue pullRequest) {
        String ignoredUsersString = trigger.getAllParticipantsIgnoredUsers();

        if(ignoredUsersString == null) {
            ignoredUsersString = "";
        }

        String[] ignoredParticipants = ignoredUsersString.split("[\\s]*,[\\s]*");

        for(BitbucketPullRequestResponseValueParticipant participant : pullRequest.getParticipants()) {
            if(!Arrays.asList(ignoredParticipants).contains(participant.getUser().getUsername()) && !participant.getApproved()) {
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
}
