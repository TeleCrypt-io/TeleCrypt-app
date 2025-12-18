package de.connect2x.tammy.telecryptModules.call.callBackend

actual class ElementCallLauncherImpl: CallLauncher {
    actual override fun launchCall() {
        val url =
            "https://call.element.io/#/?" +
                    "embed=true" +
                    "&roomName=testRoomName" +
                    "&displayName=testDisplayName"
        joinByUrl(url)
    }

    actual override fun joinByUrl(url: String) {
        val os = System.getProperty("os.name").lowercase()

        try {
            when {
                os.contains("win") ->
                    Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", url))

                os.contains("mac") ->
                    Runtime.getRuntime().exec(arrayOf("open", url))

                os.contains("nix") || os.contains("nux") ->
                    Runtime.getRuntime().exec(arrayOf("xdg-open", url))

                else -> error("Unsupported OS: $os")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    actual override fun isCallAvailable(roomId: String): Boolean {
        TODO("Not yet implemented")
    }
}