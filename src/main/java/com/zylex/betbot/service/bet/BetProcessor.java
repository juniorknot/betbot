package com.zylex.betbot.service.bet;

import com.zylex.betbot.controller.logger.BetConsoleLogger;
import com.zylex.betbot.controller.logger.LogType;
import com.zylex.betbot.exception.BetProcessorException;
import com.zylex.betbot.model.Bank;
import com.zylex.betbot.model.BetCoefficient;
import com.zylex.betbot.model.BetInfo;
import com.zylex.betbot.model.Game;
import com.zylex.betbot.service.DriverManager;
import com.zylex.betbot.service.repository.BankRepository;
import com.zylex.betbot.service.repository.BetInfoRepository;
import com.zylex.betbot.service.repository.GameRepository;
import com.zylex.betbot.service.rule.RuleNumber;
import com.zylex.betbot.service.rule.RuleProcessor;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.io.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.zylex.betbot.BetBotApplication.betStartTime;

/**
 * Making bets.
 */
@Service
public class BetProcessor {

    private BetConsoleLogger logger = new BetConsoleLogger();

    private WebDriver driver;

    private WebDriverWait wait;

    private RuleProcessor ruleProcessor;

    private BankRepository bankRepository;

    private BetInfoRepository betInfoRepository;

    private GameRepository gameRepository;

    private int totalBalance = -1;

    private int availableBalance = -1;

    @Autowired
    public BetProcessor(RuleProcessor ruleProcessor,
                        BankRepository bankRepository,
                        GameRepository gameRepository,
                        BetInfoRepository betInfoRepository) {
        this.ruleProcessor = ruleProcessor;
        this.bankRepository = bankRepository;
        this.gameRepository = gameRepository;
        this.betInfoRepository = betInfoRepository;
    }

    /**
     * Initiates non-headless chrome driver, opens the site, logs in,
     * makes bets, and saves bet made games to database.
     */
    @Transactional
    public void process(List<RuleNumber> ruleNumberList) {
        Map<RuleNumber, List<Game>> ruleGames = ruleProcessor.process();
        try {
            for (RuleNumber ruleNumber : ruleNumberList) {
                List<Game> games = ruleGames.get(ruleNumber);
                games.forEach(System.out::println);
                List<Game> betGames = findBetGames(games);
                if (!betGames.isEmpty()) {
                    openSite();
//                    processBets(ruleNumber, betGames);
                }
            }
            if (driver == null) {
                logger.betMade(LogType.NO_GAMES_TO_BET);
            } else {
                betInfoRepository.save(new BetInfo(LocalDateTime.now()));
                logger.betMade(LogType.OK);
            }
        } catch (ElementNotInteractableException | IOException e) {
            throw new BetProcessorException(e.getMessage(), e);
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    private List<Game> findBetGames(List<Game> betGames) {
        return filterByBetNotMade(filterByParsingTime(betGames));
    }

    private List<Game> filterByBetNotMade(List<Game> filteredBetGames) {
        return filteredBetGames.stream().filter(game -> !game.isBetMade()).collect(Collectors.toList());
    }

    private List<Game> filterByParsingTime(List<Game> betGames) {
        return betGames.stream()
                .filter(game -> LocalDateTime.now().isAfter(LocalDateTime.of(game.getDateTime().toLocalDate().minusDays(1), betStartTime)))
                .collect(Collectors.toList());
    }

    private void openSite() throws IOException {
        if (driver == null) {
            DriverManager driverManager = new DriverManager();
            driver = driverManager.initiateDriver(false);
            wait = new WebDriverWait(driver, 5);
            driver.navigate().to("https://1xstavka.ru/");
            logger.startLogMessage(LogType.LOG_IN, "");
            logIn();
        }
    }

    private void logIn() throws IOException {
        try (InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("BetBotAuth.properties")) {
            Properties property = new Properties();
            property.load(inputStream);
            waitSingleElementAndGet("base_auth_form").click();
            List<WebElement> authenticationForm = waitElementsAndGet("c-input-material__input");
            authenticationForm.get(0).sendKeys(property.getProperty("BetBot.login"));
            authenticationForm.get(1).sendKeys(property.getProperty("BetBot.password"));
            waitSingleElementAndGet("auth-button").click();
            checkVerify();
        }
    }

    private void checkVerify() {
        try {
            //TODO
            Thread.sleep(1500);
            String url = driver.getCurrentUrl();
            if (url.contains("accountverify")) {
                logger.logInLog(LogType.VERIFY);
            } else {
                logger.logInLog(LogType.OK);
            }
        } catch (InterruptedException e) {
            throw new BetProcessorException(e.getMessage(), e);
        }
    }

    private void processBets(RuleNumber ruleNumber, List<Game> betGames) {
        logger.startLogMessage(LogType.BET, ruleNumber.toString());
        BetCoefficient betCoefficient = ruleNumber.betCoefficient;
        updateBalance();
        int singleBetAmount = calculateAmount(ruleNumber);
        int i = 0;
        for (Game game : betGames) {
            if (availableBalance < singleBetAmount) {
                logger.noMoney();
                break;
            }
            if (!clickOnCoefficient(betCoefficient, game)) {
                game.setBetMade(false);
                gameRepository.update(game);
                continue;
            }
            if (makeBet(singleBetAmount)) {
                availableBalance -= singleBetAmount;
                game.setBetMade(true);
                gameRepository.update(game);
                logger.logBet(++i, singleBetAmount, betCoefficient, game, LogType.OK);
            }
        }
    }

    private void updateBalance() {
        if (totalBalance == -1) {
            availableBalance = (int) Double.parseDouble(waitSingleElementAndGet("top-b-acc__amount").getText());
            totalBalance = availableBalance;
            bankRepository.save(new Bank(LocalDate.now(), totalBalance));
        }
    }

    private int calculateAmount(RuleNumber ruleNumber) {
        double singleBetMoney = totalBalance * ruleNumber.percent;
        return (int) Math.max(singleBetMoney, 20);
    }

    private boolean makeBet(int amount) {
        driver.findElement(By.className("bet_sum_input")).sendKeys(String.valueOf(amount));
        WebElement betButton = waitSingleElementAndGet("coupon-btn-group__item")
                .findElement(By.cssSelector("button"));
        JavascriptExecutor executor = (JavascriptExecutor) driver;
        executor.executeScript("arguments[0].click();", betButton);
        return okButtonClick(executor);
    }

    private boolean okButtonClick(JavascriptExecutor executor) {
        try {
            WebElement okButton = waitSingleElementAndGet("o-btn-group__item")
                    .findElement(By.cssSelector("button"));
            executor.executeScript("arguments[0].click();", okButton);
        } catch (TimeoutException e) {
            WebElement okButton = waitSingleElementAndGet("swal2-confirm");
            executor.executeScript("arguments[0].click();", okButton);
            WebElement delButton = waitSingleElementAndGet("c-bet-box__del");
            executor.executeScript("arguments[0].click();", delButton);
            logger.logBet(0, 0, null, null, LogType.BET_ERROR);
            return false;
        }
        return true;
    }

    private boolean clickOnCoefficient(BetCoefficient betCoefficient, Game game) {
        List<WebElement> gameCoefficients = fetchGameCoefficients(game);
        if (gameCoefficients.isEmpty()) {
            logger.logBet(0, 0, null, game, LogType.BET_NOT_FOUND);
            return false;
        }
        gameCoefficients.get(betCoefficient.INDEX).click();
        return true;
    }

    private List<WebElement> fetchGameCoefficients(Game game) {
        driver.navigate().to("https://1xstavka.ru/line/Football/" + game.getLeague().getLink());
        List<WebElement> gameWebElements = waitElementsAndGet("c-events__item_game");
        for (WebElement gameWebElement : gameWebElements) {
            LocalDateTime dateTime = processDateTime(gameWebElement);
            if (!dateTime.equals(game.getDateTime())) {
                continue;
            }
            List<WebElement> teams = gameWebElement.findElements(By.className("c-events__team"));
            String firstTeam = teams.get(0).getText();
            String secondTeam = teams.get(1).getText();
            if (firstTeam.equals(game.getFirstTeam())
                    && secondTeam.equals(game.getSecondTeam())) {
                return gameWebElement.findElements(By.className("c-bets__bet"));
            }
        }
        return new ArrayList<>();
    }

    private LocalDateTime processDateTime(WebElement gameElement) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        String dateTime = gameElement.findElement(By.className("c-events__time"))
                .findElement(By.cssSelector("span"))
                .getText();
        String year = String.valueOf(LocalDate.now().getYear());
        dateTime = dateTime.replace(" ", String.format(".%s ", year)).substring(0, 16);
        return LocalDateTime.parse(dateTime, dateTimeFormatter);
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
