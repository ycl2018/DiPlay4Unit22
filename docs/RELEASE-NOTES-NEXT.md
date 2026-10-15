# DiPlay next release notes

Changes through DiPlay 0.2.20 are documented in [0.2.20 release notes](RELEASE-NOTES-0.2.20.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Vehicle

- The custom steering-wheel Siri key can optionally require a double press; the option is off by default. A single press remains available to the car, while the second release opens Siri after a configurable delay. The double-press interval and Siri delay are adjustable. The wheel-key service continues detecting the pair if the car assistant takes the foreground; the CarPlay window remains a fallback on cars whose assistant does not take focus.

## Changan S202 adaptations

- Route Siri capture through `VOICE_COMMUNICATION` on the reference S202. Its `VOICE_RECOGNITION` path returned about four seconds of PCM and 200 nominal 20 ms packets per wall-clock second, while two real CarPlay calls on `VOICE_COMMUNICATION` stayed at the requested rate and 50 packets/s. Siri keeps its assistant routing and does not enter call mode or enable call-only audio effects.
- Retain a bounded diagnostic tail for physical keys seen by the CarPlay window, the optional wheel-key accessibility service and Android MediaSession. Reports include action, key/scan/device codes, repeat count, navigation/session eligibility and the final consume/pass decision, without device names.
- Capture runtime state when each key is handled, retain vehicle-owned key events through redaction, and include the window's next/previous press, repeat and release events.
- Use an explicit shell-output boundary after S202 ADB validation so prompts are excluded without dropping command results when output arrives before the stdin acknowledgement, including interactive helpers.
- Add a parked-only, five-second transparent instrument-display probe for the reference Changan S202. It targets only the measured public 1920×720 HDMI presentation display, does not start CarPlay or change an OEM cluster mode, and remains separate from production cluster routing pending an in-car visibility and layering check.
