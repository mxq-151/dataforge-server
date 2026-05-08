package com.bigdata.modules.development.vo;

import java.time.Instant;
import java.util.List;

public record GitCommitInfo(
        String commitId,
        String shortMessage,
        String fullMessage,
        String authorName,
        String authorEmail,
        Instant commitTime,
        List<String> changedFiles,
        List<GitFileChange> changes
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String commitId;
        private String shortMessage;
        private String fullMessage;
        private String authorName;
        private String authorEmail;
        private Instant commitTime;
        private List<String> changedFiles = List.of();
        private List<GitFileChange> changes = List.of();

        public Builder commitId(String commitId) {
            this.commitId = commitId;
            return this;
        }

        public Builder shortMessage(String shortMessage) {
            this.shortMessage = shortMessage;
            return this;
        }

        public Builder fullMessage(String fullMessage) {
            this.fullMessage = fullMessage;
            return this;
        }

        public Builder authorName(String authorName) {
            this.authorName = authorName;
            return this;
        }

        public Builder authorEmail(String authorEmail) {
            this.authorEmail = authorEmail;
            return this;
        }

        public Builder commitTime(Instant commitTime) {
            this.commitTime = commitTime;
            return this;
        }

        public Builder changedFiles(List<String> changedFiles) {
            this.changedFiles = changedFiles;
            return this;
        }

        public Builder changes(List<GitFileChange> changes) {
            this.changes = changes;
            return this;
        }

        public GitCommitInfo build() {
            return new GitCommitInfo(
                    commitId, shortMessage, fullMessage,
                    authorName, authorEmail, commitTime,
                    changedFiles, changes
            );
        }
    }
}
