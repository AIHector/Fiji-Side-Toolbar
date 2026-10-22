// Show the Side Toolbar when Fiji starts, unless it was switched off with "Show Classic Toolbar".
if (call("ij.Prefs.get", "sidetoolbar.enabled", "true") == "true")
    run("Start Side Toolbar");
