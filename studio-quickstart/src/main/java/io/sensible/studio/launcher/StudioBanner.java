package io.sensible.studio.launcher;

public class StudioBanner {
    public static void print(int port, String runMode, String homeDir) {
        String banner = 
            "\n" +
            "  ____  _____ __  __ \n" +
            " / ___|| ____|  \\/  |\n" +
            " \\___ \\|  _| | |\\/| |\n" +
            "  ___) | |___| |  | |\n" +
            " |____/|_____|_|  |_|\n" +
            " ========================================================\n" +
            "  Sensible Studio v1.0.0-SNAPSHOT\n" +
            "  Modern, Fast, Open Source Alternative to AEM\n" +
            " ========================================================\n" +
            "  * Quickstart Directory : " + homeDir + "\n" +
            "  * HTTP Port            : " + port + "\n" +
            "  * Run Mode             : " + runMode + "\n" +
            "  * Repository Storage   : Apache Jackrabbit Oak (TarMK / Segment)\n" +
            "  * OSGi Runtime         : Apache Felix\n" +
            "  * Web Console          : http://localhost:" + port + "/system/console\n" +
            "  * JCR Browser (CRXDE)  : http://localhost:" + port + "/bin/cpm/nodes/node.html\n" +
            " ========================================================\n";
        System.out.println(banner);
    }
}
