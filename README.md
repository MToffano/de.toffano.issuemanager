# IssueManager

A plugin for JetBrains Rider: it shows the GitHub issues of the open repository in a tool window and connects them
with branches and worktrees.

## Requirements

- Rider 2026.3 or newer
- A GitHub account is configured in Rider (Settings → Version Control → GitHub). The plugin uses that account and
  needs no token of its own.
- The open project has a remote named `origin` that points to github.com.

If the login of the account has expired, the plugin offers to log in again the next time it loads.

No other plugins are needed. IssueManager only uses what Rider ships with (Git, GitHub, task management, terminal).
These bundled plugins have to be enabled: if one of them is switched off, for example the terminal, Rider does not
load IssueManager.

## Language

The texts of the plugin are available in English and German. The plugin follows the language of the operating system:
on a German system it is German, on every other system it is English. The names in this document are the English
ones.

## Installation

The plugin is not in the JetBrains Marketplace. Rider installs and updates it from this repository instead:

1. In Rider: Settings → Plugins → gear icon → "Manage Plugin Repositories…"
2. Add this address and confirm with OK:
   ```
   https://raw.githubusercontent.com/MToffano/de.toffano.issuemanager/main/deploy/updatePlugins.xml
   ```
3. Search for "IssueManager" on the "Marketplace" tab, install it and restart Rider
4. If the "Issues" tool window is not visible, show it through the three dots in the left tool window bar

Rider then offers new versions like any other plugin update. A new release can take a few minutes to show up.

Without the repository: download the ZIP from the latest release on GitHub, or build the plugin yourself (see below),
and choose it under Settings → Plugins → gear icon → "Install Plugin from Disk". An update works the same way.

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
- The open issues are grouped by milestone, "Group by" in the toolbar changes that. If no open issue has a
  milestone, the list is flat. The same goes for the other groupings.

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
| Group by | Milestone, Type, Label, Assignee, No grouping |
| Hide types and labels | one switch per issue type and per label, issues with a ticked entry are not shown |
| Open in Browser | open the selected issue on GitHub |
| Assign to me | add yourself as assignee |
| Remove my assignment | remove yourself as assignee |
| Search field | filters by number, title, assignee and branch |

The plugin remembers the switches, the sorting, the grouping and the hidden types and labels per project.

When grouped by label or assignee, an issue with several of them appears in each of their groups.

Drag an issue onto another group to change it on GitHub. Grouped by type, the issue gets the type of the group, and
"No type" removes it. Grouped by label, the issue gets the label of the group as well and keeps its other labels. The
other groupings and the closed issues take no drop.

A changed type or label shows in the list right away. The changes go to GitHub one after the other, in the order they
were made. Once all are done, the list is loaded again and shows what GitHub has stored. If GitHub refuses a change,
the status line lists it afterwards.

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
| Change type | choice of the issue types of the repository, the current one is ticked. "No type" removes it |
| Labels | all labels of the repository, the ones of the issue are ticked. A click adds or removes one label |
| Switch to branch | checks out the assigned branch |
| Open in new branch | creates a branch from the current state, checks it out and assigns it to the issue |
| Link to current branch | links the checked-out branch to the issue without checking anything out |
| Open in existing branch | choice of the local branches, checks out the chosen one and assigns it to the issue |
| Open in new worktree … | opens Rider's "New Worktree" dialog, the suggested branch name is on the clipboard |
| Open worktree | brings the Rider window of the worktree in which the issue is being worked on to the front |
| Start with | runs a command for the issue in a new terminal tab, see below |
| Copy as prompt | choice of the prompt templates, puts the prompt for the issue on the clipboard, see below |
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

One branch can be assigned to several issues: check out the branch and choose "Link to current branch" for each
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

## Start an issue with a command

"Start with" opens a new tab in Rider's terminal and runs a command there, for example a coding agent that gets the
issue as its first prompt. The terminal starts in the project folder. If the issue is being worked on in another
worktree, it starts in the folder of that worktree.

Each command has a submenu with the prompt templates, for example "Start with" → "Claude Code" → "Review". With a
single template the command starts right away.

Commands and prompt templates are set up under Settings → Tools → IssueManager. A command has a name, a command
line and the switch "Issue branch". On macOS and Linux six commands are there from the start, they can be changed or
removed. On Windows the list starts empty, see "Safety" below.

| Name | Command | Issue branch |
|---|---|---|
| Claude Code | `claude "$ISSUE_PROMPT"` | no |
| Claude Code (branch) | `claude "$ISSUE_PROMPT"` | yes |
| Claude Code (worktree) | `claude --worktree "issue-$ISSUE_NUMBER" "$ISSUE_PROMPT"` | no |
| Codex | `codex "$ISSUE_PROMPT"` | no |
| Codex (branch) | `codex "$ISSUE_PROMPT"` | yes |
| Codex (worktree) | `codex --worktree "$ISSUE_PROMPT"` | no |

There are three ways to work, and each command decides for one of them:

- **In the project folder, on the current branch:** the plain commands only open the terminal and start the tool.
  No branch is touched.
- **In the project folder, on the branch of the issue:** with "Issue branch" ticked, the plugin checks out the branch
  of the issue before the start. If the issue has no branch yet, it offers the same names as "Open in new branch",
  including "Other name …". The chosen branch is created from the current state and assigned to the issue. If the checkout does not succeed, nothing is started.
- **In a worktree, several issues at the same time:** the commands marked "(worktree)" let the tool create a worktree
  of its own, the project folder stays as it is. That needs a version of the tool that knows the `--worktree` option.
  If the branch of the issue is already checked out in a worktree, every command starts in the folder of that
  worktree.

The command gets the issue as environment variables:

| Variable | Content |
|---|---|
| `ISSUE_NUMBER` | number of the issue |
| `ISSUE_TITLE` | title |
| `ISSUE_URL` | URL on GitHub |
| `ISSUE_BRANCH` | branch of the issue, empty if it has none |
| `ISSUE_PROMPT` | the prompt, built from the chosen template |

A prompt template has a name and a text. Three templates are there from the start: "Development", "Review" and
"Research". The text may contain these placeholders:

| Placeholder | Content |
|---|---|
| `{number}` | number of the issue |
| `{title}` | title |
| `{url}` | URL on GitHub |
| `{branch}` | branch of the issue, empty if it has none |
| `{description}` | only the first post, as Markdown: title, a line with link, state, milestone, type, assignees and labels, then author, date and the description |
| `{content}` | the same as `{description}`, followed by all comments (up to 100) with author and date |

Images are included as links. As in the preview, images from private repositories can only be loaded for a few
minutes.

"Copy as prompt" puts the prompt of the chosen template on the clipboard, for a chat window or any other tool.

A prompt that is too long for an environment variable is not passed on: nothing is started and the status line says
so. The limit is 100,000 bytes, on Windows 30,000. "Copy as prompt" has no limit.

### Safety

The texts of an issue are written by other people.

- **macOS and Linux:** always write the variables in double quotes, as in the examples. The shell then passes the
  text on as exactly one argument, and it cannot add commands or options to the command line.
- **Windows:** no commands are set up from the start. PowerShell does not reliably keep a text that contains
  quotation marks in one argument, so parts of an issue could reach the tool as options of their own. Only set up a
  command there if you know how your shell passes the variable on, written as `$env:ISSUE_PROMPT` in PowerShell.
- **Everywhere:** the text is still an instruction for the tool that reads it. Check what an agent does with an issue
  from a public repository.

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
2. Run `./gradlew deploy test`. This creates `deploy/IssueManager-0.2.0.zip`, replaces the previous ZIP and writes
   the new version into `deploy/updatePlugins.xml`.
3. Commit the changes including both files
4. Create the tag and push it together with the commit:

```bash
git tag v0.2.0
git push origin main v0.2.0
```

Rider reads `deploy/updatePlugins.xml` from `main` and downloads the ZIP from the release. Push commit and tag
together, otherwise Rider offers a version whose download does not exist yet.

The workflow `.github/workflows/release.yml` then creates a release and attaches the ZIP from `deploy/`. It does not
build anything itself. If no ZIP in `deploy/` matches the tag, it stops with a message.
