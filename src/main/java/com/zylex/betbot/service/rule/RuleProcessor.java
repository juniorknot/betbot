package com.zylex.betbot.service.rule;

import com.zylex.betbot.controller.dao.BetInfoDao;
import com.zylex.betbot.controller.dao.GameDao;
import com.zylex.betbot.controller.dao.LeagueDao;
import com.zylex.betbot.controller.logger.RuleProcessorLogger;
import com.zylex.betbot.model.Game;
import com.zylex.betbot.service.Day;
import com.zylex.betbot.service.parsing.ParseProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Filter games by rules.
 */
public class RuleProcessor {

    private RuleProcessorLogger logger = new RuleProcessorLogger();

    private ParseProcessor parseProcessor;

    private LeagueDao leagueDao;

    private GameDao gameDao;

    private BetInfoDao betInfoDao;

    public RuleProcessor(ParseProcessor parseProcessor, LeagueDao leagueDao, GameDao gameDao, BetInfoDao betInfoDao) {
        this.parseProcessor = parseProcessor;
        this.leagueDao = leagueDao;
        this.gameDao = gameDao;
        this.betInfoDao = betInfoDao;
    }

    public GameDao getGameDao() {
        return gameDao;
    }

    public BetInfoDao getBetInfoDao() {
        return betInfoDao;
    }

    /**
     * Filters games by specified rule, takes list of games from site or from database,
     * which depends on current time, sort games by time, then save eligible games to file,
     * and return games list.
     * @return - map of games lists by ruleNumbers.
     */
    public Map<RuleNumber, List<Game>> process() {
        List<Game> games = parseProcessor.process();
        Map<RuleNumber, List<Game>> ruleGames = findRuleGames(games);
        return refreshByDay(ruleGames);
    }

    private Map<RuleNumber, List<Game>> refreshByDay(Map<RuleNumber, List<Game>> ruleGames) {
        LocalDateTime betTime = betInfoDao.getLast();
        Map<RuleNumber, List<Game>> betGames = new HashMap<>();
        for (RuleNumber ruleNumber : RuleNumber.values()) {
            betGames.put(ruleNumber, new ArrayList<>());
            for (Day day : Day.values()) {
                List<Game> dayGames = gameDao.getByDate(ruleNumber, LocalDate.now().plusDays(day.INDEX));
                if (betTime.isAfter(LocalDateTime.of(LocalDate.now().plusDays(day.INDEX).minusDays(1), LocalTime.of(22, 59)))) {
                    betGames.get(ruleNumber).addAll(sortByDate(dayGames));
                } else {
                    List<Game> dayRuleGames = sortByDate(ruleGames.get(ruleNumber).stream()
                            .filter(game -> game.getDateTime().toLocalDate().equals(LocalDate.now().plusDays(day.INDEX)))
                            .collect(Collectors.toList()));
                    betGames.get(ruleNumber).addAll(dayRuleGames);
                    dayGames.forEach(gameDao::delete);
                    dayRuleGames.forEach(game -> gameDao.save(game, ruleNumber));
                }
            }
        }
        logger.writeEligibleGamesNumber(betGames);
        return betGames;
    }

    private List<Game> sortByDate(List<Game> games) {
        return games.stream()
                .sorted(Comparator.comparing(Game::getDateTime))
                .collect(Collectors.toList());
    }

    private Map<RuleNumber, List<Game>> findRuleGames(List<Game> games) {
        Map<RuleNumber, List<Game>> ruleGames = new HashMap<>();
        for (RuleNumber ruleNumber : RuleNumber.values()) {
            ruleGames.put(ruleNumber, ruleNumber.rule.filter(leagueDao, games));
        }
        return ruleGames;
    }
}
