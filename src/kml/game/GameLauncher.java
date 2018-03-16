package kml.game;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import kml.Console;
import kml.Kernel;
import kml.OSArch;
import kml.Utils;
import kml.auth.Authentication;
import kml.auth.user.User;
import kml.auth.user.UserType;
import kml.exceptions.GameLauncherException;
import kml.game.profile.Profile;
import kml.game.profile.ProfileType;
import kml.game.version.Version;
import kml.game.version.VersionMeta;
import kml.game.version.Versions;
import kml.game.version.asset.AssetIndex;
import kml.game.version.library.Library;
import kml.gui.OutputFX;
import kml.gui.lang.Language;
import org.json.JSONObject;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * @author DarkLBP
 *         website https://krothium.com
 */
public class GameLauncher {

    private final Console console;
    private final Kernel kernel;
    private Process process;
    private boolean error;
    private boolean started;

    public GameLauncher(Kernel k) {
        this.kernel = k;
        this.console = k.getConsole();
    }

    /**
     * Prepares and launcher the game
     * @throws GameLauncherException If an error has been thrown
     */
    public final void launch() throws GameLauncherException {
        this.started = true;
        this.error = false;
        this.console.print("Game launch work has started.");
        Profile p = this.kernel.getProfiles().getSelectedProfile();
        if (this.isRunning()) {
            this.started = false;
            throw new GameLauncherException("Game is already started!");
        }
        Versions versions = this.kernel.getVersions();
        VersionMeta verID;
        if (p.getType() == ProfileType.CUSTOM) {
            verID = p.hasVersion() ? p.getVersionID() : versions.getLatestRelease();
        } else if (p.getType() == ProfileType.RELEASE) {
            verID = versions.getLatestRelease();
        } else {
            verID = versions.getLatestSnapshot();
        }
        if (verID == null) {
            this.started = false;
            throw new GameLauncherException("Version ID is null.");
        }
        Version ver = versions.getVersion(verID);
        if (ver == null) {
            this.started = false;
            throw new GameLauncherException("Version info could not be obtained.");
        }
        File workingDir = Kernel.APPLICATION_WORKING_DIR;
        this.console.print("Deleting old natives.");
        File nativesRoot = new File(workingDir + File.separator + "versions" + File.separator + ver.getID());
        if (nativesRoot.isDirectory()) {
            File[] files = nativesRoot.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory() && f.getName().contains("natives")) {
                        Utils.deleteDirectory(f);
                    }
                }
            }
        }
        File nativesDir = new File(workingDir, "versions" + File.separator + ver.getID() + File.separator + ver.getID() + "-natives-" + System.nanoTime());
        if (!nativesDir.isDirectory()) {
            nativesDir.mkdirs();
        }
        this.console.print("Launching Minecraft " + ver.getID() + " on " + workingDir.getAbsolutePath());
        this.console.print("Using natives dir: " + nativesDir);
        this.console.print("Extracting natives.");
        List<String> gameArgs = new ArrayList<>();
        if (p.hasJavaDir()) {
            gameArgs.add(p.getJavaDir().getAbsolutePath());
        } else {
            gameArgs.add(Utils.getJavaDir());
        }
        if (!p.hasJavaArgs()) {
            if (Utils.getOSArch() == OSArch.OLD) {
                gameArgs.add("-Xmx1G");
            } else {
                gameArgs.add("-Xmx2G");
            }
            gameArgs.add("-Xmn128M");
        } else {
            String javaArgs = p.getJavaArgs();
            String[] args = javaArgs.split(" ");
            Collections.addAll(gameArgs, args);
        }
        gameArgs.add("-Djava.library.path=" + nativesDir.getAbsolutePath());
        gameArgs.add("-cp");
        StringBuilder libraries = new StringBuilder();
        List<Library> libs = ver.getLibraries();
        String separator = System.getProperty("path.separator");
        Authentication a = this.kernel.getAuthentication();
        User u = a.getSelectedUser();
        if (u.getType() == UserType.KROTHIUM) {
            try {
                File launchPath = new File(GameLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
                libraries.append(launchPath.getAbsolutePath()).append(separator);
            } catch (URISyntaxException ex) {
                this.console.print("Failed to load GameStarter.");
            }
        }
        for (Library lib : libs) {
            if (!lib.isCompatible()) {
                continue;
            }
            if (lib.isNative()) {
                try {
                    File completePath = new File(Kernel.APPLICATION_WORKING_DIR + File.separator + lib.getRelativeNativePath());
                    FileInputStream input = new FileInputStream(completePath);
                    Utils.decompressZIP(input, nativesDir, lib.getExtractExclusions());
                } catch (IOException ex) {
                    this.console.print("Failed to extract native: " + lib.getName());
                    ex.printStackTrace(this.console.getWriter());
                }
            } else {
                File completePath = new File(Kernel.APPLICATION_WORKING_DIR + File.separator + lib.getRelativePath());
                libraries.append(completePath.getAbsolutePath()).append(separator);
            }
        }
        this.console.print("Preparing game args.");
        File verPath = new File(Kernel.APPLICATION_WORKING_DIR + File.separator + ver.getRelativeJar());
        libraries.append(verPath.getAbsolutePath());
        File assetsDir;
        AssetIndex index = ver.getAssetIndex();
        File assetsRoot = new File(workingDir, "assets");
        if ("legacy".equals(index.getID())) {
            assetsDir = new File(assetsRoot, "virtual" + File.separator + "legacy");
            if (!assetsDir.isDirectory()) {
                assetsDir.mkdirs();
            }
            this.console.print("Building virtual asset folder.");
            File indexJSON = new File(assetsRoot, "indexes" + File.separator + index.getID() + ".json");
            try {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(indexJSON.toPath()), "ISO-8859-1"));
                JSONObject objects = o.getJSONObject("objects");
                Set s = objects.keySet();
                for (Object value : s) {
                    String name = value.toString();
                    File assetFile = new File(assetsDir, name);
                    JSONObject asset = objects.getJSONObject(name);
                    String sha = asset.getString("hash");
                    if (!Utils.verifyChecksum(assetFile, sha, "SHA-1")) {
                        File objectFile = new File(assetsRoot, "objects" + File.separator + sha.substring(0, 2) + File.separator + sha);
                        if (assetFile.getParentFile() != null) {
                            assetFile.getParentFile().mkdirs();
                        }
                        Files.copy(objectFile.toPath(), assetFile.toPath());
                    }
                }
            } catch (Exception ex) {
                this.console.print("Failed to create virtual asset folder.");
                ex.printStackTrace(this.console.getWriter());
            }
        } else {
            assetsDir = assetsRoot;
        }
        gameArgs.add(libraries.toString());
        if (u.getType() == UserType.KROTHIUM) {
            gameArgs.add("kml.game.GameStarter");
            if (p.hasGameDir()) {
                File gameDir = p.getGameDir();
                if (!gameDir.isDirectory()) {
                    gameDir.mkdirs();
                }
                gameArgs.add(gameDir.getAbsolutePath());
            } else {
                gameArgs.add(workingDir.getAbsolutePath());
            }

            gameArgs.add(u.getSelectedProfile());
            gameArgs.add(u.getAccessToken());
            gameArgs.add(ver.getMainClass());
        } else {
            gameArgs.add(ver.getMainClass());
        }
        this.console.print("Full game launcher parameters: ");
        String[] versionArgs = ver.getMinecraftArguments().split(" ");
        for (int i = 0; i < versionArgs.length; i++) {
            if (versionArgs[i].startsWith("$")) {
                switch (versionArgs[i]) {
                    case "${auth_player_name}":
                        versionArgs[i] = versionArgs[i].replace("${auth_player_name}", u.getDisplayName());
                        break;
                    case "${version_name}":
                        versionArgs[i] = versionArgs[i].replace("${version_name}", ver.getID());
                        break;
                    case "${game_directory}":
                        if (p.hasGameDir()) {
                            File gameDir = p.getGameDir();
                            if (!gameDir.isDirectory()) {
                                gameDir.mkdirs();
                            }
                            versionArgs[i] = versionArgs[i].replace("${game_directory}", gameDir.getAbsolutePath());
                        } else {
                            versionArgs[i] = versionArgs[i].replace("${game_directory}", workingDir.getAbsolutePath());
                        }
                        break;
                    case "${assets_root}":
                        versionArgs[i] = versionArgs[i].replace("${assets_root}", assetsDir.getAbsolutePath());
                        break;
                    case "${game_assets}":
                        versionArgs[i] = versionArgs[i].replace("${game_assets}", assetsDir.getAbsolutePath());
                        break;
                    case "${assets_index_name}":
                        versionArgs[i] = versionArgs[i].replace("${assets_index_name}", index.getID());
                        break;
                    case "${auth_uuid}":
                        versionArgs[i] = versionArgs[i].replace("${auth_uuid}", u.getSelectedProfile());
                        break;
                    case "${auth_access_token}":
                        versionArgs[i] = versionArgs[i].replace("${auth_access_token}", u.getAccessToken());
                        break;
                    case "${version_type}":
                        versionArgs[i] = versionArgs[i].replace("${version_type}", ver.getType().name());
                        break;
                    case "${user_properties}":
                        versionArgs[i] = versionArgs[i].replace("${user_properties}", "{}");
                        break;
                    case "${user_type}":
                        versionArgs[i] = versionArgs[i].replace("${user_type}", "mojang");
                        break;
                    case "${auth_session}":
                        versionArgs[i] = versionArgs[i].replace("${auth_session}", "token:" + u.getAccessToken() + ':' + u.getSelectedProfile().replace("-", ""));
                        break;
                }
            }
        }
        Collections.addAll(gameArgs, versionArgs);
        if (p.hasResolution()) {
            gameArgs.add("--width");
            gameArgs.add(String.valueOf(p.getResolutionWidth()));
            gameArgs.add("--height");
            gameArgs.add(String.valueOf(p.getResolutionHeight()));
        }
        for (String arg : gameArgs) {
            this.console.print(arg);
        }
        ProcessBuilder pb = new ProcessBuilder(gameArgs);
        pb.directory(workingDir);
        try {
            this.process = pb.start();
            OutputFX[] out = new OutputFX[1];
            if (this.kernel.getSettings().getShowGameLog()) {
                Platform.runLater(() -> {
                    FXMLLoader loader = new FXMLLoader();
                    loader.setLocation(this.getClass().getResource("/kml/gui/fxml/Output.fxml"));
                    Parent parent;
                    try {
                        parent = loader.load();
                    } catch (IOException e) {
                        parent = null;
                        this.console.print("Failed to initialize Output GUI!");
                        e.printStackTrace(this.console.getWriter());
                    }
                    Stage stage = new Stage();
                    stage.getIcons().add(new Image("/kml/gui/textures/icon.png"));
                    stage.setTitle("Krothium Minecraft Launcher - " + Language.get(69));
                    stage.setScene(new Scene(parent));
                    stage.setResizable(true);
                    stage.setMaximized(false);
                    stage.show();
                    out[0] = loader.getController();
                });
            }
            Thread log_info = new Thread(() -> {
                try (InputStreamReader isr = new InputStreamReader(this.process.getInputStream(), StandardCharsets.ISO_8859_1);
                     BufferedReader br = new BufferedReader(isr)){
                    while (this.isRunning()) {
                        String lineRead = br.readLine();
                        if (lineRead != null) {
                            if (this.kernel.getSettings().getShowGameLog()) {
                                Platform.runLater(() -> out[0].pushString(lineRead));
                            }
                            this.console.print(lineRead);
                        }
                    }
                    if (this.process.exitValue() != 0) {
                        this.error = true;
                        this.console.print("Game stopped unexpectedly.");
                    }
                } catch (Exception ex) {
                    this.error = true;
                    this.console.print("Game stopped unexpectedly.");
                    ex.printStackTrace(this.console.getWriter());
                }
                this.started = false;
                this.console.print("Deleteting natives dir.");
                Utils.deleteDirectory(nativesDir);
            });
            log_info.start();
            Thread log_error = new Thread(() -> {
                try (InputStreamReader isr = new InputStreamReader(this.process.getErrorStream(), StandardCharsets.ISO_8859_1);
                     BufferedReader br = new BufferedReader(isr)){
                    while (this.isRunning()) {
                        String lineRead = br.readLine();
                        if (lineRead != null) {
                            if (this.kernel.getSettings().getShowGameLog()) {
                                Platform.runLater(() -> out[0].pushString(lineRead));
                            }
                            this.console.print(lineRead);
                        }
                    }
                } catch (IOException ignored) {
                    this.console.print("Failed to read game error stream.");
                    ignored.printStackTrace(this.console.getWriter());
                }
            });
            log_error.start();
        } catch (IOException ex) {
            this.error = true;
            this.started = false;
            this.console.print("Game returned an error code.");
            ex.printStackTrace(this.console.getWriter());
        }
    }

    /**
     * Checks if the game launch process is started
     * @return A boolean that indicates if the launcher process is started
     */
    public final boolean isStarted() {
        return this.started;
    }

    /**
     * Checks if the game process is running
     * @return A boolean with the current state
     */
    public final boolean isRunning() {
        return this.process != null && this.process.isAlive();
    }

    /**
     * Checks if there is an error. Once is called the error marker disappears.
     * @return If there is an error
     */
    public final boolean hasError() {
        boolean current = this.error;
        this.error = false;
        return current;
    }
}
