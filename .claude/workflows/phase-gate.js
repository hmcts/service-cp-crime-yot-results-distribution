export const meta = {
  name: 'phase-gate',
  description: 'Implement one task range test-first, gate it with read-only reviewers, remediate, repeat until every gate passes',
  whenToUse: 'One phase of an increment in this repo: a contiguous task range from specs/<n>/tasks.md, implemented in a named tree by a sole committer, then reviewed by code-reviewer, qa, spec-validator (and Codex when asked), then fixed, until every verdict is a pass.',
  phases: [
    { title: 'Implement', detail: 'one implementer over the task range, TDD, sole committer', model: 'opus' },
    { title: 'Gate', detail: 'code-reviewer, qa, spec-validator and optionally Codex, read-only, in parallel', model: 'fable' },
    { title: 'Remediate', detail: 'one fixer per round over the surviving findings', model: 'opus' },
  ],
}

// ---------------------------------------------------------------------------------------------
// args (paths absolute):
//   tree             the git tree to work in (a checkout or a worktree)                 required
//   specDir          e.g. <tree>/specs/004-release-stale-batches                          required
//   tasks            task ids in order, ["T007","T008"] or "T007 T008"                   required
//   baseCommit       the commit the range starts from; gates diff baseCommit..HEAD        required
//   lock             the gradle lock file shared by EVERY tree and EVERY agent
//                    (default /home/sachin/.cache/yotresultsdistribution/gradle.lock - fixed, not per session)
//   maxRemediations  remediation rounds before giving up (default 2 => at most 3 gates)
//   codex            true adds Codex as a fourth reviewer in every gate (default false)
//   codexFocus       extra questions for Codex, specific to this range (optional)
//   contract         coordination contract: files this tree may / may not touch (optional)
//   notes            anything else the implementer must know (optional)
//   reviewers        which repo reviewers gate the range: any of code-reviewer, qa, spec-validator
//                    (default all three); an EMPTY list skips the review loop entirely and the run
//                    ends after the implementer's green build; Codex is added separately by `codex`
//   reviewerBuilds   true lets reviewers run Gradle after the results hold (default false)
//   resume           true when a previous run of this range was interrupted: the tree may be past
//                    baseCommit and dirty; the implementer inspects and carries on (default false)
// ---------------------------------------------------------------------------------------------

const a = args || {}
for (const k of ['tree', 'specDir', 'tasks', 'baseCommit']) {
  if (!a[k]) throw new Error(`phase-gate: args.${k} is required`)
}
const TASKS = Array.isArray(a.tasks) ? a.tasks : String(a.tasks).split(/[,\s]+/).filter(Boolean)
if (!TASKS.length) throw new Error('phase-gate: args.tasks is empty')
const LOCK = a.lock || '/home/sachin/.cache/yotresultsdistribution/gradle.lock'
const MAX_REMEDIATIONS = a.maxRemediations === undefined ? 2 : a.maxRemediations
if (!Number.isInteger(MAX_REMEDIATIONS) || MAX_REMEDIATIONS < 0) throw new Error('phase-gate: args.maxRemediations must be a non-negative integer')
const RANGE = `${a.baseCommit}..HEAD`
const BUILD = `flock -w 7200 ${LOCK} ./gradlew build -Dtest.noFailFast=true`
const first = TASKS[0], last = TASKS[TASKS.length - 1]

const RULES = `
Work ONLY in the tree ${a.tree}: cd there for every command and use absolute paths.
Every Gradle invocation goes behind the shared lock: flock -w 7200 ${LOCK} ./gradlew <args>
  (two concurrent builds kill the Testcontainers workers; the lock lets several agents exist while one builds).
The full build is exactly: ${BUILD}   (noFailFast so the test results are complete when something fails).
Never enter plan mode and never stop to ask a question: nobody is watching. Decisions come from the notes below and
the spec; where something is genuinely undecided, choose the option that changes the least behaviour, say so in
open_points, and carry on. A halted run is worse than a stated assumption.
You are the SOLE COMMITTER in this tree while you run. Conventional Commits; no AI attribution anywhere
(no Co-Authored-By, no mention of Claude, AI or automated generation, in code, commits or docs).
Before EVERY commit run git status --porcelain and remove any stray file a shell redirection left in the
repo root; never commit build output or scratch files.
${a.contract ? `COORDINATION CONTRACT (files another tree owns - do not touch them; report if you had to):\n${a.contract}` : ''}
${a.notes ? `NOTES FROM THE ORCHESTRATOR:\n${a.notes}` : ''}
`

const FINDING = {
  type: 'object',
  properties: {
    severity: { type: 'string', enum: ['BLOCKER', 'HIGH', 'MEDIUM', 'LOW'] },
    file: { type: 'string' },
    line: { type: 'integer' },
    summary: { type: 'string' },
    fix_hint: { type: 'string' },
  },
  required: ['severity', 'file', 'line', 'summary', 'fix_hint'],
}
function reviewSchema(verdicts) {
  return {
    type: 'object',
    properties: {
      verdict: { type: 'string', enum: verdicts },
      findings: { type: 'array', items: FINDING },
      tree_unchanged: { type: 'boolean' },
      notes: { type: 'string' },
    },
    required: ['verdict', 'findings', 'tree_unchanged'],
  }
}
const WORK = {
  type: 'object',
  properties: {
    head_before: { type: 'string' },
    tree_clean_before: { type: 'boolean' },
    base_is_ancestor: { type: 'boolean' },
    commits: { type: 'array', items: { type: 'object', properties: { sha: { type: 'string' }, subject: { type: 'string' } }, required: ['sha', 'subject'] } },
    tasks_done: { type: 'array', items: { type: 'string' } },
    build_command: { type: 'string' },
    build_exit_code: { type: 'integer' },
    build_summary: { type: 'string' },
    git_status_after: { type: 'string' },
    open_points: { type: 'array', items: { type: 'string' } },
  },
  required: ['head_before', 'tree_clean_before', 'commits', 'tasks_done', 'build_command', 'build_exit_code', 'build_summary', 'git_status_after', 'open_points'],
}

function blocking(findings) { return (findings || []).filter(f => f.severity !== 'LOW') }
function synthetic(severity, summary, hint) {
  return { severity, file: '(workflow)', line: 0, summary, fix_hint: hint }
}

// ---- Implement -------------------------------------------------------------------------------
phase('Implement')
log(`phase-gate: ${first}..${last} in ${a.tree}`)

const implemented = await agent(`
You are the implementer for one phase of this repository. Read ${a.tree}/.claude/agents/software-engineer.md
first and obey it, then read ${a.specDir}/spec.md, plan.md, tasks.md (and research.md, data-model.md,
quickstart.md, contracts/ where present).

BEFORE ANYTHING: in ${a.tree} run git rev-parse HEAD, git status --porcelain and
git merge-base --is-ancestor ${a.baseCommit} HEAD (report its truth as base_is_ancestor).
${a.resume ? `RESUME MODE: a previous implementer of this same range was interrupted. HEAD may be past ${a.baseCommit} and
the tree may be dirty. Do not discard anything blindly: read git log --oneline ${RANGE}, git status --porcelain and
git diff, and the ticks and recorded red/green runs in ${a.specDir}/tasks.md, to establish which tasks are committed,
which are half-done in the working tree, and which are untouched. Finish the half-done work if it is sound and
belongs to a task whose red test is already committed (then commit it as that task's green step); otherwise
revert it and say so in open_points. Then continue with the untouched tasks. base_is_ancestor must be true; if it
is not, do NOT implement: return empty commits and an open_point.`
: `HEAD must be ${a.baseCommit} and the tree must be clean. If either is false, do NOT implement: return head_before,
tree_clean_before=false (or the wrong sha), empty commits and an open_point explaining what you found.`}

Implement EXACTLY these tasks, in this order, and nothing else: ${TASKS.join(', ')}.
For every task pair (test task, implementation task): land the compile-safe seams, write the failing test,
run it and record the RED run (a failing assertion, never a compile error) in tasks.md against the task,
then the minimum production code, then the GREEN run quoted the same way. One commit per task or task pair,
the test at or before the production code in every commit. Tick each task in tasks.md in the commit that
completes it.
When the range is done run the full build: ${BUILD}
It must exit 0 (suite, Checkstyle, PMD, JaCoCo gate). If it does not, fix it before returning, still test-first.
${RULES}
Return: head_before, tree_clean_before and base_is_ancestor; the commits you made (sha + subject, oldest first); the task ids
completed (in resume mode, include the range's tasks that were already committed before you started); the exact build command you ran, its exit code and a two-line summary (tests run / failed,
coverage line+branch); git status --porcelain after your last commit (the literal output, empty when clean); open_points for anything unsettled.
`, { label: `implement ${first}-${last}`, phase: 'Implement', model: 'opus', agentType: 'general-purpose', schema: WORK })

if (!implemented) throw new Error('phase-gate: the implementer returned nothing')
if (a.resume) {
  if (implemented.base_is_ancestor !== true) {
    throw new Error(`phase-gate (resume): ${a.baseCommit} is not an ancestor of HEAD ${implemented.head_before}; ${(implemented.open_points || []).join(' | ')}`)
  }
  log(`resumed from HEAD ${String(implemented.head_before).slice(0, 7)} (tree ${implemented.tree_clean_before ? 'clean' : 'dirty'} at start)`)
} else if (!implemented.tree_clean_before || !String(implemented.head_before).startsWith(String(a.baseCommit).slice(0, 7))) {
  // The harness can restart an agent mid-run (an account switch does it): the restarted implementer
  // then finds its predecessor's commits and a dirty tree. If it verified the base is an ancestor
  // and carried on, accept the result; only a tree on the wrong branch is fatal.
  if (implemented.base_is_ancestor === true) {
    log(`implementer found the tree past the base (head_before ${String(implemented.head_before).slice(0, 7)}, ${implemented.tree_clean_before ? 'clean' : 'dirty'}) and continued as a restart; accepted`)
  } else {
    throw new Error(`phase-gate: tree not at base or not clean (head_before=${implemented.head_before}, clean=${implemented.tree_clean_before}); ${(implemented.open_points || []).join(' | ')}`)
  }
}
const commits = [...implemented.commits]
log(`implemented ${implemented.tasks_done.length}/${TASKS.length} tasks in ${implemented.commits.length} commits; build exit ${implemented.build_exit_code}`)

function incomplete(work, opts) {
  const out = []
  const fixer = !!(opts && opts.fixer)   // a fixer touches only the tasks its findings name
  if (work.build_exit_code !== 0) out.push(synthetic('BLOCKER', `the build exited ${work.build_exit_code}: ${work.build_summary}`, 'make the full build exit 0, test-first'))
  // in resume mode the range may already be fully committed before this run: no new commit is fine then
  if (!work.commits.length && !a.resume) out.push(synthetic('BLOCKER', 'no commits were made', 'implement the range and commit it'))
  // implementers sometimes return "T004 - what it did" rather than the bare id: count any id they name
  const done = new Set((work.tasks_done || []).flatMap(x => String(x).match(/T\d{3}/g) || []))
  const missing = fixer ? [] : TASKS.filter(t => !done.has(t))
  if (missing.length) out.push(synthetic('BLOCKER', `tasks not completed: ${missing.join(', ')}`, 'complete every task in the range'))
  const status = String(work.git_status_after || '').trim()
  if (status && !/^\(?(clean|none|empty|nothing)\)?\.?$/i.test(status)) out.push(synthetic('HIGH', 'the tree was left dirty after the last commit', 'commit or remove the leftover files; never leave stray files'))
  return out
}

// ---- Reviewers ---------------------------------------------------------------------------------
const REVIEWERS = [
  {
    key: 'code-reviewer', agentFile: '.claude/agents/code-reviewer.md', verdicts: ['PASS', 'NEEDS CHANGES'], pass: 'PASS',
    ask: 'Review the range for logic errors, null safety, ports-and-adapters violations, swallowed exceptions, unsettled message or event paths, state written after settling, secrets, PII in logs, System.out, and the repo rules.',
  },
  {
    key: 'qa', agentFile: '.claude/agents/qa.md', verdicts: ['PASS', 'FAIL'], pass: 'PASS',
    ask: 'Judge the tests that exist against your test matrix, verify TDD discipline from the commit order and the recorded red/green runs in tasks.md, and name every coverage gap. Judge from the recorded results; the workflow, not your agent file, decides whether you may run the suite (see below).',
  },
  {
    key: 'spec-validator', agentFile: '.claude/agents/spec-validator.md', verdicts: ['COMPLIANT', 'DRIFT DETECTED'], pass: 'COMPLIANT',
    ask: 'Check the range against the message contracts, the vendored schemas, settlement discipline, state-machine completeness, the defect-fix register and the constitution as it stands in this tree.',
  },
]
if (Array.isArray(a.reviewers) && a.reviewers.length === 0) {
  REVIEWERS.length = 0
} else if (Array.isArray(a.reviewers) && a.reviewers.length) {
  const wanted = new Set(a.reviewers)
  const unknown = [...wanted].filter(k => !REVIEWERS.some(r => r.key === k))
  if (unknown.length) throw new Error(`phase-gate: unknown reviewers ${unknown.join(', ')}`)
  for (let i = REVIEWERS.length - 1; i >= 0; i--) if (!wanted.has(REVIEWERS[i].key)) REVIEWERS.splice(i, 1)
  log(`reviewers: ${REVIEWERS.map(r => r.key).join(', ')}${a.codex ? ' + codex' : ''}`)
}
if (a.codex) {
  REVIEWERS.push({
    key: 'codex', agentFile: null, verdicts: ['PASS', 'NEEDS CHANGES'], pass: 'PASS',
    ask: `Load the Codex MCP tool with ToolSearch ("select:mcp__codex__codex") and call it with cwd ${a.tree}, sandbox read-only, approval-policy never. Tell Codex, in its prompt, that it must NOT run the test suite, Gradle, Docker or any build command: it reviews by reading the code and the recorded results under ${a.tree}/build/test-results and build/reports only, because builds are slow, serialised on a shared lock and already run by the implementer. Ask Codex to review the commits ${RANGE} (git log --oneline ${RANGE}; git diff ${a.baseCommit}..HEAD) against ${a.specDir}/spec.md and plan.md, ${a.tree}/.claude/rules/design_rules.md and ${a.tree}/.specify/memory/constitution.md, looking for: swallowed or misclassified exceptions, state written after settling, races between the scheduler and any other writer, window and boundary errors, telemetry that claims something before it happened, rows that can be stranded, and anything the constitution forbids.${a.codexFocus ? ` Ask specifically about: ${a.codexFocus}` : ''} Return Codex's findings VERBATIM with file:line, severity as Codex rates them (map to BLOCKER/HIGH/MEDIUM/LOW), verdict PASS only if Codex found nothing above LOW.`,
  })
}

function reviewPrompt(r, round, declined) {
  return `
${r.agentFile ? `You are the ${r.key} for this repository: read ${a.tree}/${r.agentFile} first and obey it.` : 'You are the wrapper around an external Codex review.'}
You are READ-ONLY: never create, modify or delete a tracked or untracked file in ${a.tree}. Work in ${a.tree}
(cd there; absolute paths). Scope: the commits ${RANGE} (git log --oneline ${RANGE}; git diff ${a.baseCommit}..HEAD),
which implement tasks ${TASKS.join(', ')} of ${a.specDir}/tasks.md; read spec.md and plan.md there for intent.
${r.ask}
Also check, and raise as HIGH findings: any file touched in the range that the coordination contract gives to
another tree; any commit in the range that is not on this tree's branch; any untracked file in the repo root
(git status --porcelain).
${a.contract ? `COORDINATION CONTRACT:\n${a.contract}` : ''}
Build results: the implementer's build has just finished. Read ${a.tree}/build/test-results/test/*.xml and
${a.tree}/build/reports/jacoco/test/jacocoTestReport.xml. ${a.reviewerBuilds
    ? `Run no Gradle for the first 10 minutes so those results survive for the other reviewers; after that, if you must, use flock -w 7200 ${LOCK} ./gradlew <args>.`
    : 'Do NOT run Gradle at all: judge from those results and say in notes if a run would change your verdict.'}
${round > 1 ? `This is gate round ${round}: earlier findings were remediated in the newest commits of the range; re-check them and look for regressions.` : ''}
${declined && declined.length ? `The fixer DECLINED these earlier findings with reasons; re-judge each - either withdraw it or raise it again with why the reason does not hold:\n${declined.join('\n')}` : ''}
Before returning run git status --porcelain and git diff --stat in ${a.tree}: tree_unchanged is true only if you
changed nothing. Return the verdict exactly as one of ${JSON.stringify(r.verdicts)}, and every finding with severity,
file, line, a one-sentence summary and a fix hint.
`
}

async function runReviewers(list, round, declined) {
  return parallel(list.map(r => () =>
    agent(reviewPrompt(r, round, declined), { label: `${r.key} r${round}`, phase: 'Gate', model: 'fable', agentType: 'general-purpose', schema: reviewSchema(r.verdicts) })
      .then(v => ({ key: r.key, pass: r.pass, result: v }))))
}

async function gate(round, declined) {
  let results = await runReviewers(REVIEWERS, round, declined)
  // one slot per reviewer: relaunch a dead one once, then record it as a blocking failure
  const missing = REVIEWERS.filter((r, i) => !results[i] || !results[i].result)
  if (missing.length) {
    log(`gate r${round}: ${missing.map(r => r.key).join(', ')} returned nothing; relaunching once`)
    const again = await runReviewers(missing, round, declined)
    results = REVIEWERS.map((r, i) => {
      if (results[i] && results[i].result) return results[i]
      const j = missing.indexOf(r)
      return again[j] && again[j].result ? again[j] : { key: r.key, pass: r.pass, result: { verdict: '(none)', findings: [synthetic('BLOCKER', `${r.key} returned no result twice`, 'rerun the gate; check the reviewer prompt and tools')], tree_unchanged: true } }
    })
  }
  for (const r of results) {
    if (r.result.tree_unchanged === false) r.result.findings.push(synthetic('HIGH', `${r.key} changed the tree while reviewing`, 'reviewers are read-only; inspect git status and revert'))
    log(`gate r${round} ${r.key}: ${r.result.verdict} (${r.result.findings.length} findings, ${blocking(r.result.findings).length} blocking)`)
  }
  return results
}

function allPass(rs) {
  return rs.length === REVIEWERS.length && rs.every(r => r.result.verdict === r.pass && blocking(r.result.findings).length === 0)
}
function snapshot(round, rs) {
  return { round, reviews: rs.map(r => ({ key: r.key, verdict: r.result.verdict, findings: r.result.findings.length, blocking: blocking(r.result.findings).length })) }
}

async function remediate(round, must, may, declinedBefore) {
  phase('Remediate')
  const fixed = await agent(`
You are the remediation implementer for gate round ${round}. Read ${a.tree}/.claude/agents/software-engineer.md
first and obey it. Work in ${a.tree}. The range ${RANGE} implements tasks ${TASKS.join(', ')} of ${a.specDir}/tasks.md.
Fix EVERY finding below marked BLOCKER, HIGH or MEDIUM; fix a LOW one only if it is cheap and safe. A finding
you disagree with is not skipped silently: leave it and put the exact reason in open_points, quoting the
finding's summary, so the reviewers can re-judge it next round.
MUST FIX:
${JSON.stringify(must, null, 2)}
MAY FIX:
${JSON.stringify(may, null, 2)}
${declinedBefore.length ? `Findings declined in earlier rounds (do not re-argue unless a reviewer re-raised them):\n${declinedBefore.join('\n')}` : ''}
Every fix that changes behaviour lands test-first (red then green, recorded in tasks.md when it belongs to a
task). Small Conventional Commits, one concern each. When done run the full build: ${BUILD} - it must exit 0.
${RULES}
Return head_before and tree_clean_before (as you found the tree), the commits you made, the task ids touched,
the build command, exit code and summary, git status --porcelain after your last commit, and open_points.
`, { label: `remediate r${round}`, phase: 'Remediate', model: 'opus', agentType: 'general-purpose', schema: WORK })
  if (fixed) commits.push(...fixed.commits)
  log(`remediate r${round}: ${fixed ? fixed.commits.length : 0} commits, build exit ${fixed ? fixed.build_exit_code : 'n/a'}`)
  return fixed
}

// ---- Gate -> Remediate loop -----------------------------------------------------------------------
const history = []
let declined = [...(implemented.open_points || [])]
let remediations = 0
let round = 1
let reviews

// an incomplete implementation goes straight to remediation, not to review
let pending = incomplete(implemented)
if (pending.length) {
  log(`implementation incomplete (${pending.map(p => p.summary).join('; ')}); remediating before the first gate`)
  const fixed = await remediate(round, pending, [], declined)
  remediations += 1
  if (!fixed) throw new Error('phase-gate: the remediation implementer returned nothing before the first gate')
  declined = [...declined, ...(fixed.open_points || [])]
  const still = incomplete(fixed, { fixer: true })
  if (still.length) throw new Error(`phase-gate: still incomplete after remediation: ${still.map(p => p.summary).join('; ')}`)
}

if (Array.isArray(a.reviewers) && a.reviewers.length === 0) {
  log('phase-gate: no reviewers requested; the range ends on the implementer\'s green build (review deferred to the increment gate)')
  return { tasks: TASKS, tree: a.tree, range: RANGE, passed: true, gates: 0, remediations, history: [], commits, declined, unresolved: [], reviewed: false }
}
reviews = await gate(round, [])
history.push(snapshot(round, reviews))

while (!allPass(reviews) && remediations < MAX_REMEDIATIONS) {
  const findings = reviews.flatMap(r => r.result.findings.map(f => ({ ...f, from: r.key })))
  const must = blocking(findings)
  const may = findings.filter(f => f.severity === 'LOW')
  const fixed = await remediate(round, must, may, declined)
  remediations += 1
  if (!fixed) throw new Error(`phase-gate: the remediation implementer returned nothing in round ${round}`)
  const still = incomplete(fixed, { fixer: true })
  if (still.length) log(`remediation r${round} left the range incomplete: ${still.map(p => p.summary).join('; ')}`)
  const newlyDeclined = fixed.open_points || []
  declined = [...declined, ...newlyDeclined]
  round += 1
  reviews = await gate(round, newlyDeclined)
  if (still.length) reviews[0].result.findings.push(...still)
  history.push(snapshot(round, reviews))
}

const unresolved = reviews.flatMap(r => blocking(r.result.findings).map(f => ({ ...f, from: r.key })))
const passed = allPass(reviews)
log(`phase-gate done: ${passed ? 'ALL GATES PASS' : 'GATES NOT PASSED'} after ${round} gate(s) and ${remediations} remediation(s); ${unresolved.length} unresolved blocking findings`)

return {
  tasks: TASKS,
  tree: a.tree,
  range: RANGE,
  passed,
  gates: round,
  remediations,
  history,
  commits,
  declined,
  unresolved,
}
