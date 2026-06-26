// Shizuku UserService interface. The implementation runs in a privileged process
// (uid 2000 / ADB shell, or uid 0 / root) and executes shell commands on the agent's
// behalf, giving the app device-administrator power without root in the app process.
//
// NOTE: this lives in package com.immersive.shizuku (NOT under com.immersive.ui)
// deliberately — the AIDL tool embeds the source path into a Java comment, and a path
// segment "ui" produces "\ui", which javac misreads as an illegal \u Unicode escape.
package com.immersive.shizuku;

interface IShizukuUserService {
    // Reserved transaction id Shizuku uses on unbind so the privileged process exits.
    void destroy() = 16777114;

    // Optional explicit exit.
    void exit() = 1;

    // Run "sh -c <command>" in the privileged process and return combined output,
    // formatted as: "exit=<code>\n<output>".
    String exec(String command) = 2;
}
