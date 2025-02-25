package hudson.plugins.accurev;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.plugins.accurev.AccurevSCM.AccurevServer;
import hudson.plugins.accurev.cmd.History;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOCase;
import org.apache.commons.lang.StringUtils;

public class CheckForChanges {

  /**
   * @param server server
   * @param accurevEnv accurev environment
   * @param workspace workspace
   * @param listener listener
   * @param launcher launcher
   * @param stream stream
   * @param buildDate build Date
   * @param logger logger
   * @param scm Accurev SCm
   * @return if there are any new transactions in the stream since the last build was done
   */
  // stream param is of type AccurevStream
  public static boolean checkStreamForChanges(
      AccurevServer server,
      EnvVars accurevEnv,
      FilePath workspace,
      TaskListener listener,
      Launcher launcher,
      AccurevStream stream,
      Date buildDate,
      Logger logger,
      AccurevSCM scm) {
    String filterForPollSCM = scm.getFilterForPollSCM();
    String subPath = scm.getSubPath();
    ParseChangeLog.setSubpath(subPath);
    SimpleDateFormat formatter = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    String dateRange = formatter.format(buildDate);

    // query AccuRev for the latest transactions of each kind defined in transactionTypes using
    // getTimeOfLatestTransaction
    List<String> validTransactionTypes;
    if (stream.getType().name().equalsIgnoreCase("workspace")) {
      validTransactionTypes = AccurevSCM.DEFAULT_VALID_WORKSPACE_TRANSACTION_TYPES;
    } else {
      validTransactionTypes = AccurevSCM.DEFAULT_VALID_STREAM_TRANSACTION_TYPES;
    }
    String transactionTypes = String.join(",", validTransactionTypes);
    listener
        .getLogger()
        .println( //
            "Checking transactions of type "
                + transactionTypes
                + //
                " in stream ["
                + stream.getName()
                + "]");
    boolean isTransLatestThanBuild = false;

    Set<String> serverPaths = new HashSet<String>();
    Set<String> pollingFilters = getListOfPollingFilters(filterForPollSCM, subPath);

    try {
      final List<AccurevTransaction> tempTransaction =
          History.getTransactionsRange(
              scm,
              server,
              accurevEnv,
              workspace,
              listener,
              launcher,
              stream.getName(),
              transactionTypes,
              dateRange);
      if (tempTransaction != null && !tempTransaction.isEmpty()) {
        for (AccurevTransaction t : tempTransaction) {
          if (t.getAffectedPaths().isEmpty()
              && (t.getAction().equals("mkstream") || t.getAction().equals("chstream") || t.getAction().equals("defcomp"))) {
            listener.getLogger().println("Last valid transaction " + tempTransaction);
            isTransLatestThanBuild = true;
          } else {
            serverPaths.addAll(t.getAffectedPaths());
          }
        }
      }

      if (serverPaths.size() > 0) {
        if (changesMatchFilter(serverPaths, pollingFilters)) {
          isTransLatestThanBuild = true;
          listener.getLogger().println("Last valid transaction " + tempTransaction);
        }
      }
    } catch (Exception e) {
      final String msg =
          "getLatestTransaction failed when checking the stream "
              + stream.getName()
              + " for changes with transaction type "
              + transactionTypes;
      listener.getLogger().println(msg);
      e.printStackTrace(listener.getLogger());
      logger.log(Level.WARNING, msg, e);
    }
    return isTransLatestThanBuild;
  }

  public static boolean changesMatchFilter(
      Collection<String> serverPaths, Collection<String> filters) {
    if (CollectionUtils.isEmpty(filters)) {
      // No filters, so always a match.
      return true;
    }

    for (String path : serverPaths) {
      path = sanitizeSlashes(path);
      for (String filter : filters) {
        if (pathMatcher(path, filter)) {
          return true;
        }
      }
    }
    return false;
  }

  public static boolean pathMatcher(String path, String wildcard) {
    return FilenameUtils.wildcardMatch(path, wildcard, IOCase.INSENSITIVE);
  }

  private static Set<String> getListOfPollingFilters(String filterForPollSCM, String subPath) {
    if (StringUtils.isNotBlank(filterForPollSCM)) {
      return splitAndSanitizeFilters(filterForPollSCM);
    }

    return splitAndSanitizeFilters(subPath);
  }

  private static Set<String> splitAndSanitizeFilters(String input) {
    if (StringUtils.isBlank(input)) {
      return null;
    }

    final char DELIMITER = ',';
    final String STRIP_CHARS = " \t\n\r/";
    String[] filters = StringUtils.split(sanitizeSlashes(input), DELIMITER);
    filters = StringUtils.stripAll(filters, STRIP_CHARS);

    return new HashSet<>(Arrays.asList(filters));
  }

  private static String sanitizeSlashes(String input) {
    return input.replace('\\', '/');
  }
}
