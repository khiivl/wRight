# wRight
A (very) clever Dark Mode/Night Shift/Brightness tool for k706/QF head units.
Doesn't require root, but also can leverage root for optimal operation. 

Brightness, done right.

Features:

1. Automatic Dark Mode and Night Shift switching based on GPS time during sunrise and sunset, adjustable with a 30 minute offset. Night Shift fades smoothly over a set period of time in minutes.
2. Headlights Override feature to turn on Dark Mode during the day when the lights are on. Brightness stays the same.
3. Automatic brightness adjustment during the day, static during the night.
4. Backlight RGB adjustment from the app with HEX color input.

The app employs a clever algorithm to "prime" all the brightness values during the day, so that there is no brightness flicker during preset changes. The problem is that this only works with root, since there is no way to write the settings type these values live in. 

So without root, you will get some brightness flicker during daytime "Headlights Override" ttansition.

Overall there are 4 brightness presets on K706 head units:
1. Light mode, headlights off
2. Light mode, headlights on
3. Dark mode, headlights off
4. Dark mode, headlights on

Without root, only the current brightness is writeable. 
With root you can write into all 4 presets at the same time. 

How to use:
Install, add to sleep whitelist in 8888. 
If rooted, grant superuser rights. 
