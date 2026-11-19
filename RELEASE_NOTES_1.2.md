# wRight 1.2

Fixes for the dimmer and Night Shift, a faster first GPS fix after a sleep, and wRight as the single GPS client for the head unit.

## Fixed

- **Night Shift intensity slider did nothing on screen.** wRight wrote the colour temperature straight into the system setting, which the
  running colour service stores but does not re-apply. The temperature and the on/off state now go through the colour service's own API,
  the way the system's slider does it (with root; without root the old direct write stays as a fallback). Both are sent again after every
  start and every wake, which is also the likely cause of the old "Night Shift doesn't always work".
- **The screen pulsed in Headlights Override dark mode with the dimmer on.** The daytime brightness curve kept writing the screen while
  the dimmer was driving it, and the dimmer took the value back up to 1.5 s later. The curve now stays out of the way, and the dimmer
  checks the real brightness on every step.
- **The button backlight did not follow the dimmer in the daytime.** "Dimmer only at night" now applies to the display only. The buttons
  follow the wheel whenever the lights are on, day or night.
- **The first GPS fix after a sleep could take minutes or never come.** After a wake wRight asks for a fix every second and registers
  again every 15 s until one arrives, and again when the GPS provider comes back.
- **"No fix yet" showed a stale position.** The status panel now shows the coordinates only while a real fix arrived in the last 10 s,
  and "No GPS fix" otherwise. The position kept from before a sleep no longer counts.

## New

- **wRight is the only app that asks for GPS fixes.** It sends the speed to wFabia over the vehicle pipe as soon as a fix arrives, and
  wFabia no longer holds a location registration or permission of its own. Needs the matching wFabia build, with wRight allowed in its
  vehicle pipe card. Without it the cluster shows KEIN GPS instead of a speed.
- **ACC_OFF shuts wRight down.** The dimmer, the vehicle pipe, pending work and the root shell stop until ACC_ON, so the unit can go
  into deep sleep. The last dimmed button colour is kept for the wake, so the buttons do not flash to full brightness.
- The "Dimmer only at night" card now sits next to "Headlights from CAN".

## Notes

- Fixes are requested at the receiver's own rate. If the receiver only delivers 1 Hz, the speed cannot update faster. The log line
  `location fixes in 10 s` per provider shows the real rate (`adb logcat -s wRight`).
- Night Shift through the colour service needs root. The transaction codes (set temperature 9, set activated 7) were read from this
  ROM's framework and belong to the K706 firmware.
- The wFabia changes that go with this release: speed over the pipe, KEIN GPS on the cluster, the display loop woken by a new speed.
