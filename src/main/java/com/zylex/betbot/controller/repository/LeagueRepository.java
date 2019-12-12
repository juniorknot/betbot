package com.zylex.betbot.controller.repository;

import com.zylex.betbot.exception.LeagueRepositoryException;
import com.zylex.betbot.service.rule.RuleNumber;

import java.io.*;
import java.util.*;

/**
 * Process reading leagues files.
 */
public class LeagueRepository extends Repository {

    private File leagues;

    private Map<RuleNumber, File> ruleLeaguesFile = new HashMap<>();

    {
        leagues = new File("external-resources/leagues_list.txt");
        ruleLeaguesFile.put(RuleNumber.RULE_TEST, new File("external-resources/exclude_leagues_FIRST_RULE.txt"));
        ruleLeaguesFile.put(RuleNumber.RULE_ONE, new File("external-resources/exclude_leagues_FIRST_RULE.txt"));
    }

    /**
     * Read list of league links from total_leagues file.
     * @return - list of league links.
     */
    public List<String> readLinks() {
        return readLinksFromFile(leagues);
    }

    /**
     * Read list of league links from exclude_leagues file
     * @param ruleNumber - number of rule.
     * @return - list of league links.
     */
    public List<String> readExcludeLinks(RuleNumber ruleNumber) {
        return readLinksFromFile(ruleLeaguesFile.get(ruleNumber));
    }

    private List<String> readLinksFromFile(File file) {
        List<String> leagueLinks = new ArrayList<>();
        try (InputStream inputStream = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            reader.lines().forEach(leagueLinks::add);
        } catch (IOException e) {
            throw new LeagueRepositoryException(e.getMessage(), e);
        }
        return leagueLinks;
    }
}
