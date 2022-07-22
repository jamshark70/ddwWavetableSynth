## WavetableSynth

A Quark extension for SuperCollider, to simplify the handling of wavetables.

GPL v3, authored by James Harkins.

### Classes

- WavetablePrep: Reads wavetable data from audio files or images, and pre-processes them for frequency mapping to reduce aliasing.
-  MultiWtOsc: An oscillator pseudo-UGen that uses wavetable buffers produced by WavetablePrep.
- WavetablePrepGui: A graphical interface to visualize the interpolation and frequency mapping.
- SoundFileStream: A utility class, to simplify in-memory audio I/O.

### Dependencies

WavetablePrepGui uses my [ddwGUIEnhancements](https://github.com/jamshark70/ddwGUIEnhancements) quark.

I *believe* that the other classes use only main library methods. If you find something that is broken with a "does not understand" error, please file a bug report in this repository ("Issues").