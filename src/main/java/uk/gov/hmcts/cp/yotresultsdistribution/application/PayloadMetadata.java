package uk.gov.hmcts.cp.yotresultsdistribution.application;

/**
 * The metadata row that goes into the file service beside a payload.
 *
 * <p>The five keys are the ones progression writes, spelled the way progression spells them, because
 * the framework file service is not this service's database and systemdocgenerator is not this
 * service's reader. A sixth key, or a different spelling of one of these, is a change to somebody
 * else's contract made without asking.
 *
 * @param fileName         the document's file name, as the first record of the batch named it
 * @param conversionFormat the output format, {@code pdf}
 * @param templateName     the systemdocgenerator template, {@code OEE_Layout5}
 * @param numberOfPages    the page count progression writes, which is one
 * @param fileSize         the payload's size in bytes
 */
public record PayloadMetadata(
        String fileName,
        String conversionFormat,
        String templateName,
        int numberOfPages,
        long fileSize) {
}
