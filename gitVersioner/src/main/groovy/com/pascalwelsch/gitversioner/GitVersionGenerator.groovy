package com.pascalwelsch.gitversioner

import org.gradle.api.Project

class GitVersionGenerator {

    private final Project mProject

    private final GitVersion mGitVersion

    GitVersionGenerator(final Project project, final GitVersion gitVersion) {
        mProject = project
        mGitVersion = gitVersion
    }

    GitVersion generateVersionName() {

        // check if git project
        def status = 'git status'.execute([], mProject.rootDir)
        status.waitFor()
        def isGitProject = status.exitValue()

        if (isGitProject == 69) {
            println("git returned with error 69\n" +
                    "If you are a mac user that message is telling you is that you need to open the " +
                    "application XCode on your Mac OS X/macOS and since it hasn’t run since the last " +
                    "update, you need to accept the new license EULA agreement that’s part of the " +
                    "updated XCode.")
        }

        if (isGitProject > 0) {
            println("ERROR: can't generate a git version, this is not a git project")
            println(" -> Not a git repository (or any of the parent directories): .git")
            return new GitVersion()
        }

        // read ext properties
        Map configuration = getPropertyOrDefault(mProject.rootProject, "gitVersioner", [:]) as Map
        String[] stableBranches = configuration.get("stableBranches") ?: []
        def defaultBranch = configuration.get("defaultBranch") ?: "master"
        float yearFactor = (configuration.get("yearFactor") ?: "1000").toString().toFloat()
        boolean snapshotEnabled = configuration.get("snapshotEnabled") != false
        boolean localChangesCountEnabled = configuration.get("localChangesCountEnabled") != false
        def shortNameClosure = configuration.get("shortName")

        // get information from git
        def currentBranch = 'git symbolic-ref --short -q HEAD'.execute([], mProject.rootDir).text.trim()

        // use a defined stable branch when on such a branch
        if (stableBranches.contains(currentBranch)) {
            defaultBranch = currentBranch
        }
        def currentCommit = 'git rev-parse HEAD'.execute([], mProject.rootDir).text.trim()

        def log = "git log --pretty=format:'%at' --reverse".execute([], mProject.rootDir)
                .text.trim().readLines()
        long initialCommitDate = log.size() > 0 ? log.first().trim().replaceAll('\'', '').toLong() : 0
        def localChangesCount = 'git diff-index HEAD'.execute([], mProject.rootDir).text.trim().readLines().
                size()
        boolean hasLocalChanges = localChangesCount > 0



        def diffToDefault = "git rev-list $defaultBranch..".execute([], mProject.rootDir)
        diffToDefault.waitFor()
        if (diffToDefault.exitValue() != 0) {
            diffToDefault = "git rev-list origin/$defaultBranch..".execute([], mProject.rootDir)
        }
        def featurelines = diffToDefault.text.trim().readLines()
        def commitsInFeatureBranch = featurelines.size();

        def defaultAndFeatureLines = "git rev-list $currentCommit"
                .execute([], mProject.rootDir).text.trim().readLines()

        // the sha1 of the latest commit in the default branch
        def lastestDefaultBranchCommitSha1 = defaultAndFeatureLines.size() == 1 ?
                defaultAndFeatureLines.first() : {
            try {
                return defaultAndFeatureLines.findAll { !featurelines.contains(it) }.first()
            } catch (NoSuchElementException e) {
                // no commits found
                return currentCommit
            }
        }()

        // get additional information
        def defaultBranchDatesLog = "git log $lastestDefaultBranchCommitSha1 --pretty=format:'%at' -n 1"
                .execute([], mProject.rootDir).text.trim().replaceAll('\'', '')
        long latestCommitDate = defaultBranchDatesLog.isLong() ? defaultBranchDatesLog.toLong() :
                initialCommitDate

        // commit count is the first part of the version
        def defautBranchCommitCount = "git rev-list $lastestDefaultBranchCommitSha1 --count"
                .execute([], mProject.rootDir).text.trim()
        def commitCount = defautBranchCommitCount.isInteger() ? defautBranchCommitCount.toInteger() : 0

        // calculate the time part of the version. 2500 == 2 years, 6 months; 300 == 0.3 year
        long YEAR_IN_SECONDS = 60 * 60 * 24 * 365
        def diff = latestCommitDate - initialCommitDate


        long time = {
            if (yearFactor <= 0) {
                return 0;
            } else {
                return (diff * yearFactor / YEAR_IN_SECONDS + 0.5).intValue()
            }
        }();

        // this is the version
        def combinedVersion = commitCount + time

        def snapshot = {
            def result = ""
            if (localChangesCountEnabled && localChangesCount > 0) {
                result += "($localChangesCount)"
            }
            if (snapshotEnabled && hasLocalChanges) {
                result += "-SNAPSHOT"
            }
            return result
        }()

        def holder = new GitVersion()
        holder.version = combinedVersion
        holder.branchName = currentBranch
        holder.commit = currentCommit
        holder.branchVersion = commitsInFeatureBranch
        holder.localChanges = localChangesCount

        String featureBranchCommits = commitsInFeatureBranch == 0 ? "" : commitsInFeatureBranch
        if (featureBranchCommits.isEmpty()) {
            // return only the version when on the default branch
            holder.name = "$combinedVersion$snapshot"
            return holder
        }

        // on feature branches, add a branch identifier and the commit count
        def shortBranch = (shortNameClosure ?: {
            if (currentBranch.isEmpty()) {
                return "${currentCommit.subSequence(0, 7)}-"
            } else {
                return getTinyBranchName(currentBranch, 2)
            }
        })(holder)
        holder.shortBranch = shortBranch

        holder.name = "$combinedVersion-${shortBranch}$featureBranchCommits$snapshot"
        return holder
    }

    static def getPropertyOrDefault(Object root, String name, Object fallback) {
        return root.hasProperty(name) ? root.property(name) : fallback
    }

    static String getTinyBranchName(final String originalName, int length) {
        String nameBase64 = originalName.bytes.encodeBase64().toString()
        if (nameBase64.length() < length) {
            int charPos = originalName.size() % 26 + 65
            String lowerCase = Character.toChars(charPos).toString().toLowerCase()
            return "".padRight(2, lowerCase)
        }

        def outChars = new Integer[length]
        def chars = nameBase64.toCharArray()
        for (int i = 0; chars.size() > i; i++) {
            int c = chars[i]
            int pos = i % length
            if (outChars[pos] == null) {
                outChars[pos] = 0
            }
            int next = (c as int) + outChars[pos]
            outChars[pos] = next % 26
        }

        def result = ""
        for (int i = 0; outChars.size() > i; i++) {
            result += Character.toChars(outChars[i] + 65).toString().toLowerCase()
        }
        return result
    }

}
