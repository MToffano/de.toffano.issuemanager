# IssueManager

A plugin for JetBrains Rider: it shows the GitHub issues of the open repository in a tool window and connects them
with branches and worktrees.

## Requirements

- Rider 2026.3 or newer
- A GitHub account is configured in Rider (Settings → Version Control → GitHub). The plugin uses that account and
  needs no token of its own.
- The open project has a remote named `origin` that points to github.com.

If the login of the account has expired, the plugin offers to log in again the next time it loads.

No other plugins are needed. IssueManager only uses what Rider ships with (Git, GitHub, task management).

## Language

The texts of the plugin are available in English and German. The plugin follows the language of the operating system:
on a German system it is German, on every other system it is English. The names in this document are the English
ones.

## Installation

1. Download the ZIP from the latest release on GitHub, or build the plugin yourself (see below):
   `build/distributions/IssueManager-<version>.zip`
2. In Rider: Settings → Plugins → gear icon → "Install Plugin from Disk", choose the ZIP
3. Restart Rider
4. If the "Issues" tool window is not visible, show it through the three dots in the left tool window bar

An update works the same way: install the new ZIP and restart Rider. For plugins installed by hand Rider shows the
note "Cannot update plugin". It has no meaning here.

## The list

Each row shows number and title, followed by the branch and the assignees:

```
● #13 Offer export as PDF   ⎇ feature/13-offer-export-as-pdf   @anna
  #18 Login fails after timeout   ⎇ main   @anna, @ben
```

- **Dot in front:** the branch of this issue is checked out right now. If one branch serves several issues, all of
  them have the dot.
- **Branch in normal text:** assigned to the issue.
- **Branch in grey text:** only detected, by its name (`feature/13-…`, `fix/13-…`, `hotfix/13-…`, or the older
  `issue13`) or through an open pull request that closes the issue.
- **Row in grey text:** closed issue. Closed issues always come last.
- If the repository has milestones, the issues are grouped by them.

The status line below the list shows the repository, the number of issues and error messages.

## Toolbar

From left to right:

| Entry | Effect |
|---|---|
| Refresh | fetch the issues from GitHub again |
| Only my issues | only issues assigned to yourself |
| Only issues with a branch | hide issues without a branch |
| Show closed issues | also load closed issues |
| Sort | Newest first, Oldest first, Recently updated, Title |
| Open in Browser | open the selected issue on GitHub |
| Assign to me | add yourself as assignee |
| Remove my assignment | remove yourself as assignee |
| Search field | filters by number, title, assignee and branch |

The plugin remembers the switches and the sorting per project.

## Double-click and context menu

A **double-click** or the **Enter key** opens description and comments of the issue as a tab in the editor, including
images. The tab is read-only. Images from private repositories can only be loaded for a few minutes, another
double-click fetches them again.

The context menu (right-click on an issue):

| Entry | Effect |
|---|---|
| Show content | same as the double-click |
| Write comment … | a text field, after OK the comment is published on GitHub |
| Assign to me, Remove my assignment | same as in the toolbar |
| Switch to branch | checks out the assigned branch |
| Open in new branch | creates a branch from the current state, checks it out and assigns it to the issue |
| Open in current branch | assigns the checked-out branch to the issue without checking anything out |
| Open in existing branch | choice of the local branches, checks out the chosen one and assigns it to the issue |
| Open in new worktree … | opens Rider's "New Worktree" dialog, the suggested branch name is on the clipboard |
| Open worktree | brings the Rider window of the worktree in which the issue is being worked on to the front |
| Open in Browser | open the issue on GitHub |
| Copy URL | puts the URL of the issue on GitHub on the clipboard |
| Copy number | puts the issue number on the clipboard |

## Branch names

"Open in new branch" offers three names, plus "Other name …" for a freely chosen one:

```
feature/13-offer-export-as-pdf
fix/13-offer-export-as-pdf
hotfix/13-offer-export-as-pdf
```

- The short title consists of the first four words of the title, in lower case, joined by hyphens, with umlauts
  written as `ae`, `oe`, `ue`.
- If the issue has the label `bug` or `type: bugfix`, `fix/` comes first, otherwise `feature/`.
- `hotfix/` is meant for repositories whose pull request workflow treats hotfix branches separately.

One branch can be assigned to several issues: check out the branch and choose "Open in current branch" for each
issue. If the branch already exists locally, "Open in existing branch" does it directly. Branches whose name matches
the issue are listed first there. Branches that only exist on `origin` appear once they have been checked out in
Rider.

When an assigned branch is deleted, it disappears from the row. The issue can then be assigned again.

## Worktrees

With worktrees you can work on several issues at the same time, each in its own folder with its own Rider window.

A worktree is always created through Rider's own dialog. "Open in new worktree …" opens it and puts the suggested
branch name (for example `feature/13-offer-export-as-pdf`) on the clipboard:

1. "From branch" is the starting point, usually `main`
2. Tick "New branch" and paste the name
3. Adjust folder and project name if needed
4. Under "Copy local project setup" choose which local files should come along (for example credentials that are not
   in the repository)
5. "Create and Open Worktree"

A worktree is removed with `git worktree remove <folder>` or in Rider's worktree overview. If the folder was open in
Rider, it contains Rider settings under `.idea`, and Git asks for `--force`. Before that, run `git status` in the
worktree folder to make sure nothing but `.idea` is unsaved there.

If the branch of an issue is checked out in another worktree of the same repository, "(worktree folder name)" is
shown after the branch. For that issue all entries that give it a branch or switch to its branch are disabled.
Instead, "Open worktree" leads to the window of the worktree. When the worktree is removed, the mark disappears and
the entries are available again. A click on "Refresh" reads the worktrees again right away.

The assignment of issues to branches is kept per folder. A new worktree does not know the assignments of the main
folder, detected branches and the dot still work there.

## What the plugin changes on GitHub

Almost everything only reads. The plugin writes for these three entries only, each on an explicit click:

- Write comment …
- Assign to me
- Remove my assignment

The assignment of issues to branches is stored locally in Rider only and is not sent to GitHub.

## Limits

- The plugin shows the repository of the open project, not several repositories at once.
- The GitHub search returns at most 1000 issues per query.
- Up to 100 comments are shown per issue.

## License

MIT, see [LICENSE](LICENSE).

## Building

```bash
./gradlew buildPlugin      # ZIP in build/distributions/
./gradlew test             # tests
./gradlew deploy           # build the ZIP and put it into deploy/ (for a release)
./gradlew runIde           # start a test instance of Rider with the plugin
```

The build runs against the installed Rider in `/Applications/Rider.app`, and Gradle runs with the Java runtime
bundled with Rider. Both paths are those of a default installation on macOS, see `build.gradle.kts` and
`gradle.properties`.

Only use the test instance with a throwaway repository: branches and assignments are stored in the project folder and
would change a real project.

## Release

The ZIP is built locally and committed in the `deploy/` folder. A release is created when a version tag is pushed:

1. Set the version in `gradle.properties`, for example `version = 0.2.0`
2. Run `./gradlew deploy test`. This creates `deploy/IssueManager-0.2.0.zip` and replaces the previous ZIP.
3. Commit and push the changes including the ZIP
4. Create and push the tag:

```bash
git tag v0.2.0
git push origin v0.2.0
```

The workflow `.github/workflows/release.yml` then creates a release and attaches the ZIP from `deploy/`. It does not
build anything itself. If no ZIP in `deploy/` matches the tag, it stops with a message.
