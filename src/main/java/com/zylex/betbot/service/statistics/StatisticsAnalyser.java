package com.zylex.betbot.service.statistics;

import com.zylex.betbot.controller.Repository;
import com.zylex.betbot.controller.logger.ResultScannerConsoleLogger;
import com.zylex.betbot.controller.logger.StatisticsAnalyserConsoleLogger;
import com.zylex.betbot.exception.StatisticsAnalyserException;
import com.zylex.betbot.model.Game;
import com.zylex.betbot.model.GameResult;
import com.zylex.betbot.service.DriverManager;
import com.zylex.betbot.service.bet.rule.RuleNumber;
import org.openqa.selenium.WebDriver;

import java.io.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class StatisticsAnalyser {

    private StatisticsAnalyserConsoleLogger logger = new StatisticsAnalyserConsoleLogger();

    private Repository repository;

    private ResultScanner resultScanner;

    public StatisticsAnalyser(ResultScanner resultScanner) {
        this.resultScanner = resultScanner;
        this.repository = resultScanner.getRepository();
    }

    public void analyse(LocalDate startDate, LocalDate endDate) {
        DriverManager driverManager = new DriverManager();
        try {
            logger.startLogMessage(startDate, endDate);
            for (RuleNumber ruleNumber : RuleNumber.values()) {
                List<Game> games = repository.readTotalRuleResultFile(ruleNumber);
                repository.saveTotalRuleResultFile(ruleNumber,
                    resultScanner.process(
                        games,
                        driverManager
                    )
                );

                List<Game> gamesByDatePeriod = filterByDatePeriod(startDate, endDate, games);
                List<Game> betMadeGamesByLeagues = getBetMadeGamesByLeagues(gamesByDatePeriod);
                computeStatistics(gamesByDatePeriod, betMadeGamesByLeagues);
            }
        } catch (IOException e) {
            throw new StatisticsAnalyserException(e.getMessage(), e);
        } finally {
            driverManager.quitDriver();
        }
    }

    private List<Game> getBetMadeGamesByLeagues(List<Game> betMadeGames) throws IOException {
        List<String> leagueLinksFromFile = readLeagueLinksFromFile();
        return betMadeGames.stream()
                .filter(game -> leagueLinksFromFile.contains(game.getLeagueLink())).collect(Collectors.toList());
    }

    private List<String> readLeagueLinksFromFile() throws IOException {
        List<String> leagueLinksFromFile = new ArrayList<>();
        try (InputStream inputStream = new FileInputStream("external-resources/leagues_list.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            reader.lines().forEach(leagueLinksFromFile::add);
        }
        return leagueLinksFromFile;
    }

    private List<Game> filterByDatePeriod(LocalDate startDate, LocalDate endDate, List<Game> betMadeGames) {
        betMadeGames = betMadeGames.stream()
                .filter(game -> {
                    LocalDate gameDate = game.getDateTime().toLocalDate();
                    return (!gameDate.isBefore(startDate) && !gameDate.isAfter(endDate));
                }).collect(Collectors.toList());
        return betMadeGames;
    }

    private void computeStatistics(List<Game> games1, List<Game> games2) {
        int firstWins1 = (int) games1.stream().filter(game -> game.getGameResult().equals(GameResult.FIRST_WIN)).count();
        int ties1 = (int) games1.stream().filter(game -> game.getGameResult().equals(GameResult.TIE)).count();
        int secondWins1 = (int) games1.stream().filter(game -> game.getGameResult().equals(GameResult.SECOND_WIN)).count();
        int noResults1 = (int) games1.stream().filter(game -> game.getGameResult().equals(GameResult.NO_RESULT)).count();
        int firstWins2 = (int) games2.stream().filter(game -> game.getGameResult().equals(GameResult.FIRST_WIN)).count();
        int ties2 = (int) games2.stream().filter(game -> game.getGameResult().equals(GameResult.TIE)).count();
        int secondWins2 = (int) games2.stream().filter(game -> game.getGameResult().equals(GameResult.SECOND_WIN)).count();
        int noResults2 = (int) games2.stream().filter(game -> game.getGameResult().equals(GameResult.NO_RESULT)).count();
        logger.logStatistics(games1.size(), firstWins1, ties1, secondWins1, noResults1,
                games2.size(), firstWins2, ties2, secondWins2, noResults2);
    }
}