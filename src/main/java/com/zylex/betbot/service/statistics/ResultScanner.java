package com.zylex.betbot.service.statistics;

import com.zylex.betbot.controller.Repository;
import com.zylex.betbot.controller.logger.LogType;
import com.zylex.betbot.controller.logger.ResultScannerConsoleLogger;
import com.zylex.betbot.exception.ResultsScannerException;
import com.zylex.betbot.model.Game;
import com.zylex.betbot.model.GameResult;
import com.zylex.betbot.service.DriverManager;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@SuppressWarnings("WeakerAccess")
public class ResultScanner {

    private ResultScannerConsoleLogger logger = new ResultScannerConsoleLogger();

    private WebDriver driver;

    private WebDriverWait wait;

    private Repository repository;

    public ResultScanner(Repository repository) {
        this.repository = repository;
    }

    public void process() {
        List<Game> betMadeGames = repository.readTotalBetMadeFile();
        Map<LocalDate, List<Game>> betMadeGamesByDay = splitGamesByDay(betMadeGames);
        if (betMadeGamesByDay.isEmpty()) {
            logger.startLogMessage();
            logger.endMessage(LogType.NO_GAMES_TO_SCAN);
            return;
        }
        try {
            initiateDriver();
            logger.startLogMessage();
            if (openFootballGamesResults()) {
                processGameResults(betMadeGamesByDay);
                repository.saveTotalBetMadeGamesToFile(betMadeGames);
            }
            logger.endMessage(LogType.OK);
        } catch (IOException e) {
            throw new ResultsScannerException(e.getMessage(), e);
        } finally {
            driver.quit();
        }
    }

    private Map<LocalDate, List<Game>> splitGamesByDay(List<Game> betMadeGames) {
        Map<LocalDate, List<Game>> betMadeGamesByDay = new HashMap<>();
        Set<LocalDate> days = new HashSet<>();
        betMadeGames.forEach(game -> days.add(game.getDateTime().toLocalDate()));
        days.forEach(day -> {
            List<Game> noResultGames = findNoResultGames(betMadeGames, day);
            if (!noResultGames.isEmpty()) {
                betMadeGamesByDay.put(day, noResultGames);
            }
        });
        return betMadeGamesByDay;
    }

    private List<Game> findNoResultGames(List<Game> betsMadeGames, LocalDate day) {
        return betsMadeGames.stream()
                .filter(game -> game.getGameResult() == GameResult.NO_RESULT
                        && game.getDateTime().toLocalDate().equals(day)
                        && game.getDateTime().isBefore(LocalDateTime.now().minusHours(3)))
                .collect(Collectors.toList());
    }

    private void initiateDriver() {
        DriverManager driverManager = new DriverManager();
        driverManager.initiateDriver(true);
        driver = driverManager.getDriver();
        wait = new WebDriverWait(driver, 20);
        driver.navigate().to("https://1xstavka.ru/results/");
    }

    private boolean openFootballGamesResults() {
        WebElement footballLink = waitElementsAndGet("c-nav__link").get(1);
        if (!footballLink.getText().contains("Футбол")) {
            return false;
        }
        footballLink.click();
        waitSingleElementAndGet("c-filter_filled").click();
        return true;
    }

    private void processGameResults(Map<LocalDate, List<Game>> betsMadeGamesByDay) {
        for (Map.Entry<LocalDate, List<Game>> entry : betsMadeGamesByDay.entrySet()) {
            LocalDate day = entry.getKey();
            List<Game> betMadeGamesNoResult = entry.getValue();
            if (betMadeGamesNoResult.isEmpty()) {
                continue;
            }
            navigateToDay(day);
            waitElementsAndGet("c-games__row");
            Document document = Jsoup.parse(driver.getPageSource());
            Elements gameElements = document.select("div[class=c-games__row u-nvpd c-games__row_light c-games__row_can-toggle]");
            parseGameResults(gameElements, betMadeGamesNoResult);
        }
    }

    private void parseGameResults(Elements gameElements, List<Game> betsMadeNoResultGames) {
        AtomicInteger index = new AtomicInteger();
        for (Element gameElement : gameElements) {
            if (index.get() == betsMadeNoResultGames.size()) {
                break;
            }
            LocalDateTime date = processDateTime(gameElement);
            String[] teams = gameElement.select("div[class=c-games__opponents u-dir-ltr]").text().split(" - ");
            if (teams.length < 2 || teams[0].contains("(голы)") || teams[0].contains("/") || teams[0].contains("(люб)")) {
                continue;
            }
            String firstTeam = teams[0];
            String secondTeam = teams[1];
            String[] balls = gameElement.select("div[class=c-games__results u-mla u-tar]").text()
                    .replace(" ", "")
                    .split("\\(")[0]
                    .split(":");
            if (balls[0].contains("Голы")) {
                continue;
            }
            int firstBalls = Integer.parseInt(balls[0]);
            int secondBalls = Integer.parseInt(balls[1]);
            GameResult gameResult = computeGameResult(firstBalls, secondBalls);
            betsMadeNoResultGames.stream().filter(
                    game -> game.getDateTime().toLocalDate().equals(date.toLocalDate())
                            && firstTeam.startsWith(game.getFirstTeam())
                            && secondTeam.startsWith(game.getSecondTeam()))
                    .findFirst()
                    .ifPresent(game -> {
                        index.getAndIncrement();
                        game.setGameResult(gameResult);
                        logger.logBetMadeGame(game);
                    });
        }
    }

    private LocalDateTime processDateTime(Element element) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        String dateText = element.select("div.c-games__date").text();
        dateText = dateText.substring(0, 5) + "." + LocalDateTime.now().getYear() + dateText.substring(5);
        return LocalDateTime.parse(dateText, dateTimeFormatter);
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    private void navigateToDay(LocalDate day) {
        if (day.getMonth().equals(LocalDate.now().getMonth())) {
            waitSingleElementAndGet("vdp-datepicker").click();
            WebElement nextButton = waitSingleElementAndGet("next");
            ((JavascriptExecutor) driver).executeScript("arguments[0].click()", nextButton);
            waitElementsAndGet("day").stream()
                    .filter(element -> element.getText().equals(String.valueOf(day.getDayOfMonth())))
                    .findFirst()
                    .get()
                    .click();
        } else if (day.getMonth().equals(LocalDate.now().minusMonths(1).getMonth())) {
            waitSingleElementAndGet("vdp-datepicker").click();
            waitSingleElementAndGet("prev").click();
            waitElementsAndGet("day").stream()
                    .filter(element -> element.getText().equals(String.valueOf(day.getDayOfMonth())))
                    .findFirst()
                    .get()
                    .click();
        }
    }

    private GameResult computeGameResult(int firstBalls, int secondBalls) {
        if (firstBalls > secondBalls) {
            return GameResult.FIRST_WIN;
        } else if (firstBalls == secondBalls) {
            return GameResult.TIE;
        } else {
            return GameResult.SECOND_WIN;
        }
    }

    private List<WebElement> waitElementsAndGet(String className) {
        wait.ignoring(StaleElementReferenceException.class)
                .until(ExpectedConditions.presenceOfElementLocated(By.className(className)));
        return driver.findElements(By.className(className));
    }

    private WebElement waitSingleElementAndGet(String className) {
        wait.ignoring(StaleElementReferenceException.class)
                .until(ExpectedConditions.presenceOfElementLocated(By.className(className)));
        return driver.findElement(By.className(className));
    }
}
