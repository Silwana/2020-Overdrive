package za.co.entelect.challenge.engine.bootstrapper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import za.co.entelect.challenge.config.GameRunnerConfig;
import za.co.entelect.challenge.config.TournamentConfig;
import za.co.entelect.challenge.engine.loader.GameEngineClassLoader;
import za.co.entelect.challenge.engine.runner.GameEngineRunner;
import za.co.entelect.challenge.enums.EnvironmentVariable;
import za.co.entelect.challenge.game.contracts.bootstrapper.GameEngineBootstrapper;
import za.co.entelect.challenge.game.contracts.game.GameResult;
import za.co.entelect.challenge.game.contracts.player.Player;
import za.co.entelect.challenge.network.TournamentApi;
import za.co.entelect.challenge.network.dto.ExceptionSendDto;
import za.co.entelect.challenge.player.bootstrapper.PlayerBootstrapper;
import za.co.entelect.challenge.renderer.RendererResolver;
import za.co.entelect.challenge.storage.AzureBlobStorageService;
import za.co.entelect.challenge.utils.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class GameBootstrapper {

    private static final Logger LOGGER = LogManager.getLogger(GameBootstrapper.class);

    private Retrofit retrofit;
    private AzureBlobStorageService blobService;

    public static void main(String[] args) throws Exception {
        new GameBootstrapper().run();
    }

    private void run() throws Exception {

        GameRunnerConfig gameRunnerConfig = null;
        try {
            gameRunnerConfig = GameRunnerConfig.load("./game-runner-config.json");

            initLogging(gameRunnerConfig);
            if (gameRunnerConfig.isTournamentMode) {
                TournamentConfig tournamentConfig = gameRunnerConfig.tournamentConfig;
                blobService = new AzureBlobStorageService(tournamentConfig.connectionString);
                retrofit = new Retrofit.Builder().baseUrl(tournamentConfig.resultEndpoint)
                        .addConverterFactory(GsonConverterFactory.create()).build();

                downloadGameEngine(gameRunnerConfig);
            }

            PlayerBootstrapper playerBootstrapper = new PlayerBootstrapper();
            List<Player> players = playerBootstrapper.loadPlayers(gameRunnerConfig);

            // Class load the game engine bootstrapper. This is the entry point for the runner into the engine
            GameEngineClassLoader gameEngineClassLoader = new GameEngineClassLoader(gameRunnerConfig.gameEngineJar);
            GameEngineBootstrapper gameEngineBootstrapper = gameEngineClassLoader.loadEngineObject(GameEngineBootstrapper.class);
            gameEngineBootstrapper.setConfigPath(gameRunnerConfig.gameConfigFileLocation);
            gameEngineBootstrapper.setSeed(gameRunnerConfig.seed);

            RendererResolver rendererResolver = new RendererResolver(gameEngineBootstrapper);

            GameEngineRunner engineRunner = new GameEngineRunner.Builder()
                    .setGameRunnerConfig(gameRunnerConfig)
                    .setGameEngine(gameEngineBootstrapper.getGameEngine())
                    .setGameMapGenerator(gameEngineBootstrapper.getMapGenerator())
                    .setRoundProcessor(gameEngineBootstrapper.getRoundProcessor())
                    .setReferee(gameEngineBootstrapper.getReferee())
                    .setRendererResolver(rendererResolver)
                    .setPlayers(players)
                    .build();

            GameResult gameResult = engineRunner.runMatch();

            if (gameRunnerConfig.isTournamentMode) {
                notifyMatchComplete(gameRunnerConfig.tournamentConfig, gameResult);
            }

        } catch (Exception e) {
            LOGGER.error("Exception during match execution", e);
            notifyMatchFailure(gameRunnerConfig, e);
        } finally {
            if (gameRunnerConfig != null && gameRunnerConfig.isTournamentMode) {
                File zippedLogs = ZipUtils.createZip(gameRunnerConfig.matchId, gameRunnerConfig.roundStateOutputLocation);
                saveMatchLogs(gameRunnerConfig.tournamentConfig, zippedLogs);
            }
        }
        System.exit(0);
    }

    private void downloadGameEngine(GameRunnerConfig gameRunnerConfig) throws Exception {
        LOGGER.info("Downloading game engine");
        File gameEngineZip = blobService.getFile(
                System.getenv(EnvironmentVariable.GAME_ENGINE.name()),
                String.format("tournament-tmp/engine-%s.zip", UUID.randomUUID().toString()),
                gameRunnerConfig.tournamentConfig.gameEngineContainer);

        File gameEngineDir = ZipUtils.extractZip(gameEngineZip).getParentFile();
        gameRunnerConfig.gameEngineJar = Objects.requireNonNull(gameEngineDir.listFiles((dir, name) -> name.endsWith(".jar")))[0].getPath();
        gameRunnerConfig.gameConfigFileLocation = Objects.requireNonNull(gameEngineDir.listFiles(
                (dir, name) -> name.endsWith(".json") || name.endsWith(".properties")
        ))[0].getPath();
    }

    private void initLogging(GameRunnerConfig gameRunnerConfig) {
        if (gameRunnerConfig.isVerbose) {
            Configurator.setRootLevel(Level.DEBUG);
        } else {
            Configurator.setRootLevel(Level.WARN);
        }

        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig config = ctx.getConfiguration().getRootLogger();
        Appender appender = FileAppender.newBuilder()
                .withName("File")
                .withFileName(String.format("%s/match.log", gameRunnerConfig.gameName))
                .withLayout(PatternLayout.newBuilder().withPattern("%d{HH:mm:ss,SSS} %p %m%n").build())
                .build();
        config.addAppender(appender, Level.ALL, config.getFilter());
    }

    private void saveMatchLogs(TournamentConfig tournamentConfig, File matchLogs) throws Exception {
        LOGGER.info("Saving match logs to storage");
        blobService.putFile(matchLogs, tournamentConfig.matchLogsPath, tournamentConfig.matchLogsContainer);
        LOGGER.info("Done saving match logs to storage");
    }

    private void notifyMatchComplete(TournamentConfig tournamentConfig, GameResult gameResult) {
        LOGGER.info("Notifying of match completion");

        gameResult.tournamentId = tournamentConfig.tournamentId;
        gameResult.playerAEntryId = System.getenv(EnvironmentVariable.PLAYER_A_ENTRY_ID.name());

        Gson gson = new GsonBuilder().create();
        LOGGER.info(() -> gson.toJson(gameResult));

        LOGGER.info(gameResult);
        TournamentApi tournamentApi = retrofit.create(TournamentApi.class);
        try {
            Call<Void> call = tournamentApi.updateMatchStatus(tournamentConfig.functionKey, gameResult);
            Response<Void> execute = call.execute();

            if (!execute.isSuccessful()) {
                LOGGER.error("Failed to notify match completion: {}", execute.errorBody() != null ? execute.errorBody().string() : "");
            }

        } catch (Exception e) {
            LOGGER.error("Failed to notify match completion:", e);
        }
    }

    private void notifyMatchFailure(GameRunnerConfig gameRunnerConfig, Exception ex) {
        if (gameRunnerConfig != null && gameRunnerConfig.isTournamentMode) {
            LOGGER.info("Notifying of match failure");

            GameResult gameResult = new GameResult();
            gameResult.isSuccessful = false;
            gameResult.matchId = gameRunnerConfig.matchId;
            gameResult.verificationRequired = true;
            gameResult.tournamentId = gameRunnerConfig.tournamentConfig.tournamentId;
            gameResult.playerAId = gameRunnerConfig.playerAId;
            gameResult.playerBId = gameRunnerConfig.playerBId;
            gameResult.playerOnePoints = 0;
            gameResult.playerTwoPoints = 0;
            gameResult.roundsPlayed = 0;
            gameResult.winner = "";
            gameResult.playerAEntryId = System.getenv(EnvironmentVariable.PLAYER_A_ENTRY_ID.name());

            try {
                TournamentApi tournamentApi = retrofit.create(TournamentApi.class);

                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                String exceptionAsString = sw.toString();
                ExceptionSendDto exceptionSendDto = new ExceptionSendDto(
                        exceptionAsString,
                        System.getenv(EnvironmentVariable.PLAYER_A_ENTRY_ID.name())
                );
                tournamentApi.addExceptionForTracing(exceptionSendDto).execute();
                tournamentApi.updateMatchStatus(gameRunnerConfig.tournamentConfig.functionKey, gameResult).execute();
            } catch (IOException e) {
                LOGGER.error("Error notifying failure", e);
            }
        }
    }
}
