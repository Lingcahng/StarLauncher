import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;


public class MinecraftLauncher {
    private static int TIME_VERSION=0;
    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";//Bugjang的api
    private static final String RESOURCES_URL = "https://resources.download.minecraft.net";//src
    private static final String LIBRARIES_URL = "https://libraries.minecraft.net";//lib
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final String GAME_DIR = "F:/Star_White_launcher";;//F盘绝对路径
    private static final String ASSETS_DIR = GAME_DIR + "/assets";
    private static final String ASSETS_DIR_INDEX = GAME_DIR + "/assets/indexes";
    private static final String LIBRARIES_DIR = GAME_DIR + "/libraries";
    private static final String VERSIONS_DIR = GAME_DIR + "/versions";
    private static final String Java_DIR = "C:/Users/Administrator/.jdks";
    private static final int JVM_XMX=3;//堆内存按GB算


    public static void main(String[] args) {

        try {
            // 初始化目录
            initDirectories();
            // 获取版本清单
            JSONObject manifest = fetchJson(VERSION_MANIFEST_URL);
            JSONArray versions = manifest.getJSONArray("versions");
            // 显示可用版本并让用户选择
            String selectedVersion = selectVersion(versions,Game_set());
            System.out.println("选中的版本 " + selectedVersion);
            // 获取版本详情
            String versionUrl = findVersionUrl(versions, selectedVersion);
            TIME_VERSION=findVersionlasttime(versions, selectedVersion);
            JSONObject versionData = fetchJson(versionUrl);
            // 下载游戏文件
            downloadGameFiles(versionData, selectedVersion);
            // 启动游戏
            launchGame(versionData, selectedVersion);
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private static void initDirectories() throws IOException {
        Files.createDirectories(Paths.get(GAME_DIR));
        Files.createDirectories(Paths.get(ASSETS_DIR));
        Files.createDirectories(Paths.get(ASSETS_DIR_INDEX));
        Files.createDirectories(Paths.get(LIBRARIES_DIR));
        Files.createDirectories(Paths.get(VERSIONS_DIR));
        Files.createDirectories(Paths.get(Java_DIR));
        Files.createDirectories(Paths.get(GAME_DIR, "natives"));
    }
    private static String Game_set(){
        while(true){
            System.out.println("你想游玩哪个类型的版本？：快照版[1];正式版[2];远古版[3]");
            switch (new Scanner(System.in).nextInt()){
                case 1:
                    return "snapshot";
                case 2:
                    return "release";
                case 3:
                    return "old_alpha";
                default:
                    System.out.print("参数错误");
            }
        }
    }
    private static String selectVersion(JSONArray versions,String type) {
        System.out.println("可用版本列表:");
        // 显示1.0至今
        int count = 0;
        List<String> releaseVersions = new ArrayList<>();
        for (int i = 0; i < versions.length() && count < 100; i++) {
            JSONObject version = versions.getJSONObject(i);
            if (type.equals(version.getString("type"))) {
                System.out.println((count + 1) + ". " + version.getString("id"));
                releaseVersions.add(version.getString("id"));
                count++;
            }
        }
        //用户选择
        Scanner scanner = new Scanner(System.in);
        System.out.print("请选择版本(1-" + count + "): ");
        int choice = scanner.nextInt();
        if (choice < 1 || choice > count) {
            System.out.println("无效选择，使用默认版本 1.0");
            return "1.0";
        }
        return releaseVersions.get(choice - 1);
    }
    private static void downloadGameFiles(JSONObject versionData, String versionId) throws Exception {
        System.out.println("开始下载游戏文件...");
        //下载客户端JAR
        JSONObject downloads = versionData.getJSONObject("downloads");
        String clientJarPath = VERSIONS_DIR + "/" + versionId + "/" + versionId + ".jar";
        downloadFile(downloads.getJSONObject("client").getString("url"), clientJarPath);
        //下载资源文件
        downloadAssets(versionData.getJSONObject("assetIndex"));
        //下载依赖库
        downloadLibraries(versionData.getJSONArray("libraries"));
        //提取原生库
        extractNatives(versionData, versionId);
        System.out.println("游戏文件下载完成!");
    }

    private static void downloadAssets(JSONObject assetIndex) throws Exception {
        String assetsUrl = assetIndex.getString("url");
        String assetsId = assetIndex.getString("id");
        JSONObject assetsData = fetchJson(assetsUrl);
        Writer write = assetsData.write(new FileWriter(ASSETS_DIR+"/indexes/"+assetsId+".json"),4,0);
        //写入后及时关闭文件
        write.close();
        JSONObject objects = assetsData.getJSONObject("objects");
        System.out.println("开始下载资源文件...");//写的比较烂，下载很慢
        int total = objects.length();
        int count = 0;
        for (String key : objects.keySet()) {
            JSONObject obj = objects.getJSONObject(key);
            String hash = obj.getString("hash");
            String path = hash.substring(0, 2) + "/" + hash;
            File targetFile = new File(ASSETS_DIR + "/objects/" + path);
            if (!targetFile.exists()) {
                String url = RESOURCES_URL + "/" + path;
                downloadFile(url, targetFile.getAbsolutePath());
            }
            count++;
            if (count % 100 == 0 || count == total) {
                System.out.printf("下载资源: %d/%d (%.1f%%)\r", count, total, (count * 100.0 / total));
            }
        }
        System.out.println("\n资源文件下载完成!");
    }
    private static void downloadLibraries(JSONArray libraries) throws Exception {
        System.out.println("开始下载依赖库...");
        int total = libraries.length();
        int count = 0;
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject lib = libraries.getJSONObject(i);
            //跳过不适用于当前操作系统的库，比较重要
            if (lib.has("rules") && !isRuleAllowed(lib.getJSONArray("rules"))) {
                System.out.println("跳过不适用库: " + lib.getString("name"));
                continue;
            }
            JSONObject downloads = lib.optJSONObject("downloads");
            if (downloads != null) {
                //下载主库文件
                if (downloads.has("artifact")) {
                    JSONObject artifact = downloads.getJSONObject("artifact");
                    String url = artifact.getString("url");
                    String path = LIBRARIES_DIR + "/" + artifact.optString("path", getLibraryPath(lib));
                    downloadFile(url, path);
                }
                //下载原生库
                if (downloads.has("classifiers")) {
                    JSONObject classifiers = downloads.getJSONObject("classifiers");
                    String nativeKey = getNativeClassifier();
                    if (classifiers.has(nativeKey)) {//判断是否存在
                        JSONObject nativeLib = classifiers.getJSONObject(nativeKey);
                        String url = nativeLib.getString("url");
                        String path = LIBRARIES_DIR + "/" + nativeLib.optString("path",
                                getLibraryPath(lib) + "-" + nativeKey + ".jar");
                        downloadFile(url, path);
                    }
                }
            } else {
                //旧版库处理
                String url = LIBRARIES_URL + "/" + getLibraryPath(lib);
                String path = LIBRARIES_DIR + "/" + getLibraryPath(lib);
                downloadFile(url, path);
            }
            count++;
            System.out.printf("下载库文件: %d/%d (%.1f%%)\r", count, total, (count * 100.0 / total));
        }
        System.out.println("\n依赖库下载完成!");
    }
    private static String getLibraryPath(JSONObject lib) {
        String name = lib.getString("name");
        String[] parts = name.split(":");
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
    }
    private static void extractNatives(JSONObject versionData, String versionId) throws Exception {
        JSONArray libraries = versionData.getJSONArray("libraries");
        Path nativesDir = Paths.get(GAME_DIR, "natives");
        System.out.println("开始提取原生库...");
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject lib = libraries.getJSONObject(i);
            JSONObject downloads = lib.optJSONObject("downloads");
            if (downloads != null && downloads.has("classifiers")) {
                JSONObject classifiers = downloads.getJSONObject("classifiers");
                String nativeKey = getNativeClassifier();
                if (classifiers.has(nativeKey)) {
                    JSONObject nativeLib = classifiers.getJSONObject(nativeKey);
                    String url = nativeLib.getString("url");
                    String path = LIBRARIES_DIR + "/" + getLibraryPath(lib) + "-" + nativeKey + ".jar";
                    downloadFile(url, path);
                    //解压原生库
                    unzipFile(path, nativesDir.toString());
                }
            }
        }
        System.out.println("原生库提取完成!");
    }
    //根据操作系统类型返回对应的原生库分类标识符（纯粹闲的223）
    private static String getNativeClassifier() {
        if (OS_NAME.contains("win")) return "natives-windows";
        if (OS_NAME.contains("mac")) return "natives-osx";
        return "natives-linux";
    }

    //解压器（应该没问题）
    private static void unzipFile(String zipPath, String destDir) throws IOException {
        Path destPath = Paths.get(destDir);
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(zipPath)) {
            Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                Path entryPath = destPath.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream in = zipFile.getInputStream(entry);
                         OutputStream out = new FileOutputStream(entryPath.toFile())) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = in.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }
    //下载器
    private static void downloadFile(String url, String targetPath) throws Exception {
        File targetFile = new File(targetPath);
        if (targetFile.exists()) {
            return;
        }
        Files.createDirectories(targetFile.toPath().getParent());
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Minecraft");
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }
    private static void launchGame(JSONObject versionData, String versionId) throws Exception {
        System.out.println("准备启动游戏...");
        //构建类路径
        String classpath = buildClasspath(versionData, versionId);
        //准备启动命令
        List<String> command = new ArrayList<>();
        if(TIME_VERSION<2044){
            System.out.println("Minecraft版本过低，将使用Java8启动");
            System.out.println("Java构建中......");
            if(!new File(Java_DIR+"/jdk8u452-b09/bin/java.exe").exists()){
                downloadFile("https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jdk/x64/windows/OpenJDK8U-jdk_x64_windows_hotspot_8u452b09.zip",Java_DIR+"/java.zip");
                unzipFile(Java_DIR+"/java.zip",Java_DIR);
                Process process = Runtime.getRuntime().exec("setx -m path= %java8%;"+Java_DIR+"/bin");
            }
            System.out.println("Java构建完成！");
            command.add(Java_DIR + "/jdk8u452-b09/bin/java");
        }else{
            command.add(System.getProperty("java.home") + "/bin/java");//识别Java
        }
        //JVM参数
        command.add("-Djava.library.path=" + new File(GAME_DIR, "natives").getAbsolutePath());
        command.add("-Xms"+JVM_XMX*1024+"m");
        command.add("-Xmx"+JVM_XMX*1024+"m");
        command.add("-cp");

        command.add(classpath); //使用绝对类路径
        //识别主类，老版本好像有的不是mainClass
        String mainClass = versionData.getString("mainClass");
        command.add(mainClass);
        //游戏参数
        JSONArray gameArgs = getGameArguments(versionData, versionId);
        for (int i = 0; i < gameArgs.length(); i++) {
            if (gameArgs.get(i) instanceof String) {
                String arg = gameArgs.getString(i);
                command.add(arg);
            }
        }
        //启动命令，分析用
        System.out.println("启动命令: " + String.join(" ", command));
        // 启动游戏，新版旧版一起强制兼容(缺点，1.12.2及已下老版本无法启动qwq)
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(GAME_DIR));
        pb.inheritIO();
        Process process = pb.start();
        // 启动线程读取错误流，读到一些错误，不到是什么
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.err.println(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
        // 启动线程读取输出流
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
        // 等待游戏结束
        int exitCode = process.waitFor();
        System.out.println("游戏已退出，代码: " + exitCode);
    }
    private static JSONArray getGameArguments(JSONObject versionData, String versionId) {
        JSONArray gameArgs = new JSONArray();
        if (versionData.has("arguments") && versionData.getJSONObject("arguments").has("game")) {
            gameArgs = versionData.getJSONObject("arguments").getJSONArray("game");
        } else if (versionData.has("minecraftArguments")) {
            // 旧版本兼容
            String[] args = versionData.getString("minecraftArguments").split(" ");
            for (String arg : args) {
                gameArgs.put(arg);
            }
        }
        // 替换占位符
        for (int i = 0; i < gameArgs.length(); i++) {
            if (gameArgs.get(i) instanceof String) {
                String arg = gameArgs.getString(i);
                arg = arg.replace("${version_name}", versionId)
                        .replace("${version_type}","正式版")
                        .replace("${assets_index_name}", versionData.getJSONObject("assetIndex").getString("id"))
                        .replace("${assets_root}", ASSETS_DIR)
                        .replace("${game_directory}", GAME_DIR)
                        .replace("${auth_player_name}", "Player")
                        .replace("${auth_uuid}", UUID.randomUUID().toString())
                        .replace("${auth_access_token}", "0")
                        .replace("${user_type}", "legacy")
                        .replace("${resolution_width}", "1280")
                        .replace("${resolution_height}", "720");
                gameArgs.put(i, arg);
            }
        }
        return gameArgs;
    }
    //依赖检查
    private static String buildClasspath(JSONObject versionData, String versionId) {
        List<String> classpathEntries = new ArrayList<>();
        //JAR路径（直接使用绝对路径）
        String clientJar = VERSIONS_DIR + "/" + versionId + "/" + versionId + ".jar";
        classpathEntries.add(clientJar); // 直接添加绝对路径
        //添加所有依赖库
        JSONArray libraries = versionData.getJSONArray("libraries");
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject lib = libraries.getJSONObject(i);
            // 跳过不适用库（有的加载会崩掉）
            if (lib.has("rules") && !isRuleAllowed(lib.getJSONArray("rules"))) {
                continue;
            }
            // 处理新版库格式
            if (lib.has("downloads")) {
                JSONObject downloads = lib.getJSONObject("downloads");
                if (downloads.has("artifact")) {
                    JSONObject artifact = downloads.getJSONObject("artifact");
                    String path = artifact.optString("path", getLibraryPath(lib));
                    classpathEntries.add(LIBRARIES_DIR + "/" + path);
                }
            }
            //处理旧版库格式（）
            else if (lib.has("name")) {
                String path = getLibraryPath(lib);
                classpathEntries.add(LIBRARIES_DIR + "/" + path);
            }
        }
        //确保包含 lwjgl 等关键库
        addCriticalLibraries(classpathEntries, "lwjgl");
        addCriticalLibraries(classpathEntries, "jinput");
        addCriticalLibraries(classpathEntries, "log4j");
        //类路径分隔符（Windows用; Linux/Mac用:）
        return String.join(File.pathSeparator, classpathEntries);
    }
    private static void addCriticalLibraries(List<String> classpath, String libraryName) {
        classpath.addAll(
                classpath.stream().filter(path -> path.contains(libraryName)).collect(Collectors.toList())
        );
    }
    private static boolean isRuleAllowed(JSONArray rules) {
        // 默认允许
        boolean allow = true;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            if (rule.has("action")) {
                String action = rule.getString("action");
                if ("allow".equals(action)) {
                    if (rule.has("os")) {
                        allow = checkOsRule(rule.getJSONObject("os"));
                    } else {
                        allow = true;
                    }
                } else if ("disallow".equals(action)) {
                    if (!rule.has("os") || checkOsRule(rule.getJSONObject("os"))) {
                        return false;
                    }
                }
            }
        }
        return allow;
    }
    private static boolean checkOsRule(JSONObject osRule) {
        if (osRule.has("name")) {
            String osName = osRule.getString("name");
            String currentOS = OS_NAME.toLowerCase();

            if ("windows".equals(osName) && !currentOS.contains("win"))
                return false;
            if ("osx".equals(osName) && !currentOS.contains("mac"))
                return false;
            if ("linux".equals(osName) &&
                    !currentOS.contains("linux") && !currentOS.contains("nix"))
                return false;
        }
        //检查架构要求
        if (osRule.has("arch")) {
            String arch = System.getProperty("os.arch");
            String requiredArch = osRule.getString("arch");

            if ("x86".equals(requiredArch) && !"x86".equals(arch) && !"i386".equals(arch))
                return false;
        }
        return true;
    }
    private static String findVersionUrl(JSONArray versions, String targetVersion) {
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.getJSONObject(i);
            if (targetVersion.equals(version.getString("id"))) {
                return version.getString("url");
            }
        }
        throw new RuntimeException("没有发现版本qwq: " + targetVersion);
    }
    private static int findVersionlasttime(JSONArray versions, String targetVersion) {
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.getJSONObject(i);
            if (targetVersion.equals(version.getString("id"))) {
                String [] times=version.getString("releaseTime").substring(0, 10).split("-");
                int sum=0;
                for(String a:times){
                    sum=Integer.parseInt(a)+sum;
                }
                return sum;
            }
        }
        throw new RuntimeException("没有发现版本qwq: " + targetVersion);
    }
    private static JSONObject fetchJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Minecraft");
        try (InputStream is = conn.getInputStream()) {
            return new JSONObject(new String(is.readAllBytes()));
        }
    }

}


