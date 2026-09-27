package uk.gov.hmcts.cp.yotresultsdistribution.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One line a class can write, read out of the class's own source.
 *
 * <p>For the claim a privacy suite makes about a whole leg. "No recipient's address at any level"
 * is a statement about <em>every</em> line the leg can write, and a suite that drove a list of
 * cases would be making it about the lines somebody remembered - a statement added next month is
 * outside such a claim from the moment it is written, silently, with the suite still green. So the
 * statements are enumerated here out of the sources instead, and the suite insists its drive
 * reached each of them: a new line is then a failing test until it has a case, which is the only
 * way round that fails loudly.
 *
 * <p>What identifies a statement is its <strong>message pattern</strong> - {@code
 * ILoggingEvent.getMessage()}, the format string before any argument is substituted - beside the
 * logger that wrote it. Caller data would be the obvious key and is the wrong one: logback resolves
 * it lazily by taking a stack trace when it is first asked, so an event whose caller data is first
 * read on the asserting thread reports the <em>asserting</em> thread's stack. That is the same trap
 * {@link CapturedLog} documents for the MDC, and the pattern is not subject to it.
 *
 * <p><strong>Which is why the key has to be unique, and a sweep has to say so.</strong> Two
 * statements in one class that spell the same pattern have the same key, and the event from either
 * satisfies both declarations - so a line added beside one that already writes that wording would
 * never have to be reached, and the sweep that exists to catch exactly that would pass anyway.
 * {@link #keyCollisionsIn(List)} is what a sweep refuses on, asserted before it asserts anything
 * about what a drive reached. Keying on the caller instead would close the same hole and cost more
 * than it is worth twice over: the stack it would read is the asserting thread's, per the paragraph
 * above, and a key carrying a line number would make a statement that only moved line fail its own
 * declaration.
 *
 * <p>The scan is deliberately literal about this repository's style rather than a parser: every
 * logging statement in it begins its own line with {@code LOG.}, and the pattern is the leading
 * string literal, concatenated across as many lines as it is written over. A statement written any
 * other way is not matched, which is why a suite using this asserts a floor on the count - a scan
 * that had quietly stopped matching anything would fall below it rather than pass.
 *
 * @param loggerName the logger that writes it, which is the declaring class
 * @param pattern    the message pattern, exactly as the source spells it
 * @param where      the source file and line, for the assertion message that names what was missed
 */
public record LogStatement(String loggerName, String pattern, String where) {

    /** Where a class's source lives, from its binary name. */
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /** The characters the pattern is read by, named so an {@code if} carries no literal. */
    private static final char QUOTE = '"';

    private static final char CONCATENATION = '+';

    private static final char ESCAPE = '\\';

    private static final char NEWLINE_ESCAPE = 'n';





    private static final char TAB_ESCAPE = 't';


    /** The five level names, as they are written after {@code LOG.}. */
    private static final List<String> LEVELS =
            List.of("error", "warn", "info", "debug", "trace");

    /** The five calls this repository writes a line with. */
    private static final List<String> CALLS =
            List.of("LOG.error(", "LOG.warn(", "LOG.info(", "LOG.debug(", "LOG.trace(");


    /**
     * What the suite matches a captured event against: the logger and the pattern it wrote.
     *
     * @return the key this statement is recognised by
     */
    public String key() {
        return loggerName + "|" + pattern;
    }

    /**
     * The statements no sweep over these can tell apart, by the locations that share a key.
     *
     * <p>Asserted empty before anything is asserted about what a drive reached, because two
     * statements on one key make the reach assertion vacuous for the second of them: the event
     * from whichever the drive did reach satisfies both, and the other sits inside no claim at all
     * with the suite still green. The fix is on the source rather than here - give one of the two
     * its own wording - which is the same answer as for a line the drive never reached.
     *
     * @param statements the enumerated declarations, as {@link #everyOneIn(List)} answers them
     * @return one entry per shared key, naming every source location that spells it
     */
    public static List<String> keyCollisionsIn(final List<LogStatement> statements) {
        final Map<String, List<String>> locations = statements.stream()
                .collect(Collectors.groupingBy(LogStatement::key, LinkedHashMap::new,
                        Collectors.mapping(LogStatement::where, Collectors.toList())));
        return locations.values().stream()
                .filter(shared -> shared.size() > 1)
                .map(shared -> String.join(" and ", shared))
                .toList();
    }

    /**
     * Every line in the production sources that attaches a throwable, as
     * {@code file:line catches <the types caught>}, at every level the privacy rule governs.
     *
     * <p><strong>The claim this makes is by construction, and it is the only kind worth making
     * about a whole class of defect.</strong> Five times a statement wrote a bounded reason and
     * then attached the throwable as well, and the message an attached throwable renders belongs
     * to whoever raised it - a driver, a pool, an HTTP client, the broker - so it carries whatever
     * that library chose to put in it, which on a store or transport failure is where a connection
     * string or a fragment of a statement turns up. A suite that pinned each one as it was found
     * would go on finding them; a sweep that refuses the shape cannot be added to without failing.
     *
     * <p><strong>Nothing may be attached, and the flat rule is the only one that holds.</strong>
     * Two softer rules were tried and both were defeated inside one review. A hand-kept list of
     * this service's own exception types let three wrappers through, because a cause chain renders
     * recursively. Deriving the answer from the type - attachable where no constructor takes a
     * {@link Throwable} - lasted no longer: every exception inherits {@code initCause}, so a type
     * with no such constructor can still be given a cause after it is built, a subclass can
     * declare one, and nothing about a constructor's signature says the message it composes is
     * bounded. What is left is the rule that needs no exceptions: a line carries the class of what
     * was caught and never the throwable.
     *
     * <p>The enclosing {@code catch} is resolved <strong>lexically</strong>, by the brace structure
     * around the statement, rather than by matching the argument's name against the catches in the
     * file: a name reused by a later catch would otherwise excuse an earlier attachment. A
     * multi-catch is reported under every type it declares, because no type is attachable and the
     * entry should say what was caught.
     *
     * @return one entry per offending statement, empty where the sweep's claim holds
     * @throws IOException if a source cannot be read
     */
    public static List<String> attachmentsInProductionSources() throws IOException {
        final List<String> attached = new ArrayList<>();
        try (java.util.stream.Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (final Path source : sources.filter(each -> each.toString().endsWith(".java"))
                    .sorted().toList()) {
                attached.addAll(attachmentsIn(
                        Files.readString(source, StandardCharsets.UTF_8),
                        source.getFileName().toString()));
            }
        }
        return attached;
    }

    /**
     * The same sweep over one source, so the scan itself can be put in front of a case.
     *
     * <p>A scan is a claim about every line in the repository, and a claim that large is worth
     * nothing if the scan quietly stops matching: this is how a suite hands it source it wrote and
     * asserts what it finds, including every shape that has defeated a previous version of it.
     *
     * @param source   the source text
     * @param fileName what to call it in an entry
     * @return one entry per offending statement in it
     */
    public static List<String> attachmentsIn(final String source, final String fileName) {
        final List<String> attached = new ArrayList<>();
        final javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        final javax.tools.SimpleJavaFileObject unit =
                new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///" + fileName),
                        javax.tools.JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
                        return source;
                    }
                };
        final com.sun.source.util.JavacTask task = (com.sun.source.util.JavacTask) compiler
                .getTask(null, null, diagnostic -> {}, null, null, List.of(unit));
        try {
            final com.sun.source.util.SourcePositions where =
                    com.sun.source.util.Trees.instance(task).getSourcePositions();
            // One source per call, so one unit: the loop is the API's shape rather than a
            // repetition, and the scanner is built for the unit it walks.
            final com.sun.source.tree.CompilationUnitTree parsed =
                    task.parse().iterator().next();
            new AttachmentScanner(parsed, where, fileName, attached).scan(parsed, null);
        } catch (IOException notParsed) {
            throw new IllegalStateException("the sweep could not parse " + fileName, notParsed);
        }
        return attached;
    }

    /**
     * The walk that decides what is attached, over the compiler's own tree.
     *
     * <p><strong>Written against a parse rather than the text, because the text defeated six
     * versions of this.</strong> Each fix recognised one more way of spelling the same argument -
     * a cast, then brackets, then a cast inside brackets - and the shapes are unbounded, so the
     * scan was always one review behind. A tree has no spellings: {@code failed},
     * {@code (failed)}, {@code ((Throwable) failed)} and {@code failed.getCause()} are four
     * arrangements of one fact, that the argument came from the caught exception.
     *
     * <p><strong>What counts as attaching it is what the expression evaluates to, not whether the
     * caught name appears in it.</strong> Mentioning the exception is ordinary and safe - a bounded
     * violation code, a classification, a response code, a type name - and a rule that flagged
     * every mention reported four such lines the first time it was run, which is how a sweep
     * teaches its readers to ignore it. What is attached is the throwable itself, reached through
     * any number of brackets and casts, or a throwable taken off it: {@code getCause} is the one
     * accessor on {@link Throwable} that answers another one.
     */
    private static final class AttachmentScanner
            extends com.sun.source.util.TreeScanner<Void, Void> {

        private final com.sun.source.tree.CompilationUnitTree unit;
        private final String fileName;
        private final List<String> attached;
        private final java.util.Deque<com.sun.source.tree.CatchTree> open =
                new java.util.ArrayDeque<>();
        private final com.sun.source.util.SourcePositions where;

        private AttachmentScanner(final com.sun.source.tree.CompilationUnitTree unit,
                final com.sun.source.util.SourcePositions where, final String fileName,
                final List<String> attached) {
            this.unit = unit;
            this.where = where;
            this.fileName = fileName;
            this.attached = attached;
        }

        @Override
        public Void visitCatch(final com.sun.source.tree.CatchTree node, final Void ignored) {
            open.push(node);
            try {
                return super.visitCatch(node, ignored);
            } finally {
                open.pop();
            }
        }

        @Override
        public Void visitMethodInvocation(
                final com.sun.source.tree.MethodInvocationTree node, final Void ignored) {
            if (!open.isEmpty() && isALogCall(node) && !node.getArguments().isEmpty()) {
                final com.sun.source.tree.ExpressionTree last =
                        node.getArguments().getLast();
                final com.sun.source.tree.CatchTree enclosing = open.peek();
                final String caught = enclosing.getParameter().getName().toString();
                if (isAttachment(last, caught)) {
                    attached.add(fileName + ":" + lineOf(node) + " catches " + typesOf(enclosing));
                }
            }
            return super.visitMethodInvocation(node, ignored);
        }

        /** Whether the call is one of this repository's five logging calls. */
        private static boolean isALogCall(final com.sun.source.tree.MethodInvocationTree node) {
            return node.getMethodSelect() instanceof com.sun.source.tree.MemberSelectTree select
                    && "LOG".equals(select.getExpression().toString())
                    && LEVELS.contains(select.getIdentifier().toString());
        }

        /**
         * Whether the expression hands the caught throwable to the line.
         *
         * <p>Three arrangements, and the tree makes them one question. Brackets and casts are
         * written round the same object and are unwrapped. A bare name is the object. And
         * {@code getCause} is the one accessor on {@link Throwable} that answers another
         * throwable, so a chain of them is still somebody's message.
         *
         * @param expression the final argument
         * @param caught     the name the enclosing catch bound
         * @return true where what is passed is the throwable or one taken off it
         */
        private static boolean isAttachment(
                final com.sun.source.tree.ExpressionTree expression, final String caught) {
            final boolean attachment;
            if (expression instanceof com.sun.source.tree.ParenthesizedTree brackets) {
                attachment = isAttachment(brackets.getExpression(), caught);
            } else if (expression instanceof com.sun.source.tree.TypeCastTree cast) {
                attachment = isAttachment(cast.getExpression(), caught);
            } else if (expression instanceof com.sun.source.tree.IdentifierTree name) {
                attachment = caught.equals(name.getName().toString());
            } else if (expression instanceof com.sun.source.tree.MethodInvocationTree call
                    && call.getMethodSelect()
                            instanceof com.sun.source.tree.MemberSelectTree select
                    && "getCause".equals(select.getIdentifier().toString())) {
                attachment = isAttachment(select.getExpression(), caught);
            } else {
                attachment = false;
            }
            return attachment;
        }

        /** Every type the catch declares, a multi-catch giving more than one. */
        private static String typesOf(final com.sun.source.tree.CatchTree enclosing) {
            return enclosing.getParameter().getType().toString().replace("|", " | ")
                    .replaceAll(" +", " ").strip();
        }

        private long lineOf(final com.sun.source.tree.Tree node) {
            return unit.getLineMap().getLineNumber(where.getStartPosition(unit, node));
        }
    }

    /**
     * Every line the given classes can write, in source order.
     *
     * @param types the classes to read; each must have its source under {@code src/main/java}
     * @return one entry per logging statement found
     * @throws IOException if one of the sources cannot be read
     */
    public static List<LogStatement> everyOneIn(final List<Class<?>> types) throws IOException {
        final List<LogStatement> statements = new ArrayList<>();
        for (final Class<?> type : types) {
            statements.addAll(everyOneIn(type));
        }
        return statements;
    }

    private static List<LogStatement> everyOneIn(final Class<?> type) throws IOException {
        final Path source = SOURCE_ROOT.resolve(type.getName().replace('.', '/') + ".java");
        final List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        final List<LogStatement> statements = new ArrayList<>();

        for (int line = 0; line < lines.size(); line++) {
            final String text = lines.get(line);
            if (CALLS.stream().anyMatch(text.strip()::startsWith)) {
                statements.add(new LogStatement(
                        type.getName(),
                        patternFrom(lines, line, text.indexOf('(', text.indexOf("LOG.")) + 1),
                        type.getSimpleName() + ".java:" + (line + 1)));
            }
        }
        return statements;
    }

    /**
     * The leading string literal of one statement, concatenated over as many lines as it takes.
     *
     * <p>Reading stops at the first character of the argument list that is not whitespace, not a
     * {@code +} joining two literals and not a quote opening the next one - which is the comma
     * before the first argument, or the closing bracket of a statement that has none.
     *
     * @param lines the whole source
     * @param first the line the statement opens on
     * @param from  the column just after the opening bracket
     * @return the pattern, unescaped as the compiler would unescape it
     */
    private static String patternFrom(final List<String> lines, final int first, final int from) {
        final String remaining = lines.get(first).substring(from) + "\n"
                + String.join("\n", lines.subList(first + 1, lines.size()));
        final StringBuilder pattern = new StringBuilder();
        int column = 0;
        boolean reading = true;

        while (reading && column < remaining.length()) {
            final char character = remaining.charAt(column);
            if (character == QUOTE) {
                column = literalFrom(remaining, column + 1, pattern);
            } else if (Character.isWhitespace(character) || character == CONCATENATION) {
                column++;
            } else {
                reading = false;
            }
        }
        return pattern.toString();
    }

    /**
     * One string literal's contents, appended to the pattern being built.
     *
     * @param text    the source from the opening bracket on
     * @param from    the column just after the opening quote
     * @param pattern where the contents are appended
     * @return the column just after the closing quote
     */
    private static int literalFrom(final String text, final int from,
            final StringBuilder pattern) {

        int column = from;
        while (column < text.length() && text.charAt(column) != QUOTE) {
            if (text.charAt(column) == ESCAPE && column + 1 < text.length()) {
                pattern.append(unescaped(text.charAt(column + 1)));
                column += 2;
            } else {
                pattern.append(text.charAt(column));
                column++;
            }
        }
        return column + 1;
    }

    /** The one character an escape sequence stands for, for the three this repository writes. */
    private static char unescaped(final char escaped) {
        final char character;
        if (escaped == NEWLINE_ESCAPE) {
            character = '\n';
        } else if (escaped == TAB_ESCAPE) {
            character = '\t';
        } else {
            character = escaped;
        }
        return character;
    }
}
