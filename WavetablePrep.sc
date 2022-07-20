// 2022 hjh H. James Harkins

WavetablePrep {
	classvar <>filters;

	var <>path;
	var <>wtSize = 2048;
	var <>numMaps = 8;
	var <>ratio = 2;
	var <>fudgeFactor = 0.5;
	var <>filter = \brickwall;
	// 2d array of Signals
	// outer dimension is wt-position entries
	// inner dimension is mipmaps
	var <>tables;

	*initClass {
		filters = (
			brickwall: { |fft, topBin|
				var r = fft.real.copy, i = fft.imag.copy;
				topBin = topBin.asInteger + 1;
				r[topBin .. r.size - topBin] = 0.0;
				i[topBin .. r.size - topBin] = 0.0;
				r[0] = 0.0; i[0] = 0.0;  // also kill DC
				Complex(r, i)
			},
			tenPctSlope: { |fft, topBin|
				var r = fft.real.copy, i = fft.imag.copy;
				var half = (topBin * 0.05).asInteger;
				var step;
				// number of items = topBin * 0.1
				// total line should slide over -1 / (topBin * 0.1)
				// = -10 / topBin
				topBin = topBin.asInteger;
				step = -10.0 / topBin;
				((topBin * 0.9).asInteger .. topBin).do { |bin, j|
					var mul = 1.0 + (step * j);
					j = j + half;
					r[j] = r[j] * mul;
					i[j] = i[j] * mul;
				};
				topBin = topBin + half + 1;
				r[topBin .. r.size - topBin] = 0.0;
				i[topBin .. r.size - topBin] = 0.0;
				r[0] = 0.0; i[0] = 0.0;  // also kill DC
				Complex(r, i)
			}
		)
	}

	*new { |path, wtSize = 2048, numMaps = 8, ratio = 2, filter|
		^super.newCopyArgs(path, wtSize, numMaps, ratio, 0.5, filter)
	}

	read { |action, pause = 0.01|
		{
			var file = SoundFile.openRead(path);
			var block;
			if(file.isNil) {
				action.value(Error("File open failed"))
			} {
				tables = Array.new(file.numFrames div: wtSize);
				protect {
					while {
						block = Signal.newClear(wtSize);
						file.readData(block);
						block.size == wtSize
					} {
						tables = tables.add(this.decimate(block));
						if(pause > 0) { pause.wait };
					};
				} { |exception|
					file.close;
					action.value(exception, this);
				};
			};
			// action.value(nil, this);
		}.fork(AppClock);
	}

	decimate { |timeDomainTable, cos(Signal.fftCosTable(wtSize))|
		var fft = timeDomainTable.fft(Signal.newClear(wtSize), cos);
		var func = filters[filter] ?? { filters[\brickwall] };
		^Array.fill(numMaps, { |i|
			var topBin = timeDomainTable.size * 0.5 / (ratio ** (i + fudgeFactor));
			var new = func.(fft, topBin);
			new.real.ifft(new.imag, cos).real
		});
	}

	write { |outPath|
		var file = SoundFile.openWrite(outPath,
			"WAV", "float", 1, 44100
		);
		if(file.isNil) {
			Error("Could not open file '%' for writing".format(outPath.basename)).throw;
		};
		protect {
			tables.do { |row|  // row = one wtpos
				row.do { |wt|
					file.writeData(wt)
				}
			}
		} { file.close };
	}
}

MultiWtOsc {
	*ar { |freq = 440, wtPos = 0, squeeze = 0, wtOffset = 0,
		bufnum = 0, wtSize = 2048, numTables = 8, ratio = 2,
		numOscs = 1, detune = 1, interpolation = 2|

		var out = this.arOscs(freq, wtPos, squeeze, wtOffset,
			bufnum, wtSize, numTables, ratio,
			numOscs, detune, interpolation
		);
		^out.asArray.sum
	}

	*arOscs { |freq = 440, wtPos = 0, squeeze = 0, wtOffset = 0,
		bufnum = 0, wtSize = 2048, numTables = 8, ratio = 2,
		numOscs = 1, detune = 1, interpolation = 2|

		var detunes = Array.fill(numOscs, { detune ** Rand(-1, 1) });

		var log = log(ratio);
		var baseFreq = SampleRate.ir / wtSize;
		// logarithm (base ratio) of freq / baseFreq
		// I'm also going to use an ugly workaround
		// to stick a conditional into a 'var' block
		// this must be ar before rounding!
		// else kr --> ar interpolation will accidentally
		// offset the buffer read position
		var mapIndex = {
			var index = ((log(freq) - log(baseFreq)) / log).clip(0, numTables - 1.001);
			if(index.rate == \control) {
				K2A.ar(index);
			} {
				index
			}
		}.value;

		var evenMap = mapIndex.round(2) * wtSize;
		var oddMap = ((mapIndex + 1).round(2) - 1) * wtSize;
		var mapXfade = mapIndex.fold(0, 1);

		var rowSize = wtSize * numTables;
		var lagPos = Lag.kr(wtPos, 0.15);
		var evenWt = lagPos.round(2) * rowSize;
		var oddWt = ((lagPos + 1).round(2) - 1) * rowSize;
		var wtXfade = lagPos.fold(0, 1) * 2 - 1;

		var normphase = Phasor.ar(0, SampleDur.ir * (freq * detunes), 0, 1);
		// credit: Paul Miller of TXModular
		var phaseDist = ((normphase * 2 - 1) ** (2 ** squeeze)) * 0.5 + 0.5;
		var phase = (phaseDist + wtOffset) % 1.0 * wtSize;

		var evenPhase = phase + evenMap;  // eliminate a duplicate '+'
		var evenSig = LinXFade2.ar(
			BufRd.ar(1, bufnum, evenPhase + evenWt, interpolation: interpolation),
			BufRd.ar(1, bufnum, evenPhase + oddWt, interpolation: interpolation),
			wtXfade
		);
		var oddPhase = phase + oddMap;
		var oddSig = LinXFade2.ar(
			BufRd.ar(1, bufnum, oddPhase + evenWt, interpolation: interpolation),
			BufRd.ar(1, bufnum, oddPhase + oddWt, interpolation: interpolation),
			wtXfade
		);

		^LinXFade2.ar(evenSig, oddSig, mapXfade * 2 - 1).unbubble
	}
}
