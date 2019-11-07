package com.zylex.betbot.controller.logger;

import com.zylex.betbot.model.Game;
import org.apache.commons.lang3.StringUtils;

public class ResultScannerConsoleLogger extends ConsoleLogger{

    public synchronized void startLogMessage() {
        writeInLine("\nScanning results:");
    }

    public void logBetMadeGame(int index, Game game) {
        writeInLine(String.format("\n%d) Using rule: %s, match result %s, for game: %s",
                index,
                StringUtils.join(game.getRuleNumberSet(), ", "),
                game.getGameResult(),
                game));
    }

    public void endMessage() {
        writeInLine("\nResults are scanned.");
        writeLineSeparator();
        writeInLine(String.format("\nBot work completed in %s", computeTime(programStartTime.get())));
    }
}
