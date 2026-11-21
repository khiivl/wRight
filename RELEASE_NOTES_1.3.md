# wRight 1.3

A calmer wake from sleep: nothing time-dependent is applied from a wrong clock, the buttons stay dark until the car's lights state is known, and the system's own Night Light schedule no longer fights wRight.

## Changed

- **A staged wake.** On ACC_ON, wRight starts GPS after half a second and sends the settings that do not depend on the time of day (button
  colour, the lights state to the MCU, stock auto dark mode off) after one second. Dark mode, Night Shift and the brightness curve wait
  for the first real GPS fix of the wake - or for a 5 second fallback with the last known position and the clock. Duplicate ACC_ON and
  quickboot broadcasts restart the sequence cleanly, and ACC_OFF and quickboot power-off go through one sleep path that cancels the pending wake work.
- **Invalid CAN state means lights off, full stop.** With "Headlights from CAN" on, the lights are on only while wFabia's state is valid
  and says on. After a wake, with the CANable missing or with wFabia not running, the lights are off - the old 10 second hold of the last
  state and the fallback to the MCU's own state are gone. The MCU is told "lights off" while the state is invalid too, not only when it is
  valid and off. With "Headlights from CAN" off, the MCU's own wire still decides.
- **The button backlight goes out at ACC_OFF.** The MCU is told "lights off" and the stored `persist.sys.headlight_enable` is set to false,
  so the buttons no longer light for the moments between the start and wRight correcting the state. The lights state is dropped with the ignition.

## Fixed

- **The system's Night Light schedule fought wRight.** Its custom hours or twilight mode switched Night Shift off behind wRight's back at
  its boundaries and after a wake. With Night Shift on, wRight now turns the system's schedule off, once, only if it was on.
- **A cached GPS fix could pass for a fresh clock reading.** A fix is stamped with the time it was really taken, so a cached position from
  before a sleep no longer makes the clock wrong after a boot or wake until a real fix arrives.
- **The sleep anchor survived a reboot.** The anchor that carries the time over a sleep now stores the boot count, and an anchor from before
  a reboot is ignored instead of projecting a wildly wrong time.
- **The boot receiver started too early.** It was direct-boot aware and listened for `LOCKED_BOOT_COMPLETED`, which races the unit's own
  storage and MCU start. It now starts the service after a full boot, on quickboot, after an app update (nothing else restarted it before)
  and on ACC_ON as a safety net.

## Misc

- **wFabia CAN integration fixes** (the matching wFabia build is needed, with wRight allowed in its vehicle pipe card):
  - wFabia drops the lights state with the ignition - at ACC_OFF, again at ACC_ON, and when the CAN link goes down - and says "invalid"
    until the CANable delivers a frame. Before, the last state of the previous drive could be reported after a night's sleep, because the
    stale test counts in a clock that stands still while the unit is suspended.
  - The USB attach helper no longer brings wFabia to the foreground at every ACC_ON (Zlink resets the USB bus on each start).
- The speed hand-over to wFabia is logged with the fix counts (`adb logcat -s wRight_Service`).

## Notes

- With "Headlights from CAN" on and no CANable or wFabia, wRight never switches the lights on. That is by design.
- Dark mode, Night Shift and brightness can now apply up to about five seconds after a wake, where before they applied at once from the
  cached position.
- Checked on a bench unit: the staged wake, the gate opening on the first fix, the lights-off call at ACC_OFF and while the state is
  invalid, and the pipe reconnecting. Not checked there: the lights switching on from real CANable frames.
