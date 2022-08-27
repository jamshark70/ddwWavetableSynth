// 2022 hjh H. James Harkins

WavetablePrep {
	classvar <>filters;

	var <>path;
	var <>wtSize = 2048;
	var <>numMaps = 8;
	var <>ratio = 2;
	var <>fudgeFactor = 0.5;
	var <>filter = \tenPctSlope;
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

	*read { |path, wtSize = 2048, numMaps = 8, ratio = 2, filter,
		action, pause = 0|
		^this.new(path, wtSize, numMaps, ratio, filter)
		.read(action, pause)
	}

	*readImage { |path, wtSize = 2048, numPos = 128, numMaps = 8, ratio = 2, valueFunc, filter|
		var image = Image.open(path);
		^this.fromImage(image, wtSize, numPos, numMaps, ratio, valueFunc, filter)
	}

	// note: in-place operation on the image object
	*fromImage { |image, wtSize = 2048, numPos = 128, numMaps = 8, ratio = 2, valueFunc, filter|
		^this.new(nil, wtSize, numMaps, ratio, filter)
		.fromImage(image, numPos, valueFunc);
	}

	*readFromProcessedFile { |path, wtSize = 2048, numMaps = 8, ratio = 2,
		startFrame = 0, numFrames = -1|
		^this.new("", wtSize, numMaps, ratio)
		.readFromProcessedFile(path, startFrame, numFrames)
	}

	read { |action, pause = 0|
		var file = SoundFile.openRead(path);
		if(file.isNil) {
			action.value(Error("File open failed"))
		} {
			this.readStream(file, action, pause);
		};
	}

	readStream { |file, action, pause = 0|
		var block;
		{
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
		}.fork(AppClock);
	}

	fromImage { |image, numPos = 128, valueFunc({ |color| color.luminance })|
		var pix;
		var cos = Signal.fftCosTable(wtSize);
		if(image.width != wtSize or: { image.height != numPos }) {
			image.scalesWhenResized_(true)
			.setSize(wtSize, numPos)
		};
		pix = image.pixels;
		tables = Array.fill(numPos, { |i|
			var row = pix[i * wtSize .. (i+1) * wtSize - 1]
			.collect { |pix| valueFunc.(Image.pixelToColor(pix)) * 2 - 1 };
			this.decimate(row.as(Signal), cos)
		});
	}

	decimate { |timeDomainTable, cos(Signal.fftCosTable(wtSize))|
		var fft = timeDomainTable.fft(Signal.newClear(wtSize), cos);
		var func = filters[filter] ?? { filters[\tenPctSlope] };
		^Array.fill(numMaps, { |i|
			var topBin = timeDomainTable.size * 0.5 / (ratio ** (i + fudgeFactor));
			var new = func.(fft, topBin);
			new.real.ifft(new.imag, cos).real.normalize
		});
	}

	blendAt { |wtPos = 0, freq = 1, sr = 44100|
		var baseFreq = sr / wtSize;
		// log_base_ratio
		var map = (log(freq / baseFreq) / log(ratio)).clip(0, numMaps - 1.001);
		var map1 = floor(map + 1);
		var pos = wtPos.clip(0, tables.size - 1.001);
		var pos1 = floor(pos + 1);
		var lowmap = blend(tables[pos.floor][map.floor], tables[pos1][map.floor], pos.frac);
		var himap = blend(tables[pos.floor][map1], tables[pos1][map1], pos.frac);
		^blend(lowmap, himap, map.frac)
	}

	readFromProcessedFile { |path, startFrame = 0, numFrames = -1|
		var file = SoundFile.openRead(path);
		if(file.notNil) {
			file.seek(startFrame, 0);
			this.readProcessedStream(file, numFrames);
		} {
			Error("Could not open '%' for reading".format(path.basename)).throw;
		}
	}

	readProcessedStream { |file, numFrames = -1|
		var chunk;
		if(numFrames < 0) {
			numFrames = file.numFrames;
		};
		tables = Array.new;
		protect {
			while {
				numFrames >= wtSize and: {
					chunk = Signal.newClear(wtSize);
					file.readData(chunk);
					chunk.size == wtSize
				}
			} {
				tables = tables.add(chunk);
				numFrames = numFrames - wtSize;
			};
		} { file.close };
		tables = tables.clump(numMaps);
	}

	write { |outPath|
		var file = SoundFile.openWrite(outPath,
			"WAV", "float", 1, 44100
		);
		if(file.isNil) {
			Error("Could not open file '%' for writing".format(outPath.basename)).throw;
		};
		protect {
			this.writeStream(file)
		} { file.close };
	}

	writeStream { |file|
		tables.do { |row|  // row = one wtpos
			row.do { |wt|
				file.writeData(wt)
			}
		}
	}

	gui { |parent, bounds|
		^WavetablePrepGui(this, parent, bounds)
	}
}

SoundFileStream : CollStream {
	var <>headerFormat = "WAV", <>sampleFormat = "float";
	var <>numChannels = 1, <>sampleRate = 44100;

	*new { |collection|
		^super.newCopyArgs(collection.as(Signal))  // this handles 'nil' too!
	}

	numFrames {
		^collection.size div: numChannels
	}
	duration {
		^collection.size div: numChannels / sampleRate
	}

	close { this.pos = 0 }
	seek { |frames, origin = 0|
		switch(origin)
		{ 0 } {
			pos = frames * numChannels
		}
		{ 1 } {
			pos = pos + (frames * numChannels)
		}
		{ 2 } {
			pos = collection.size + (frames * numChannels)
		};
		pos = pos.clip(0, collection.size);
	}

	readData { |floatArray|
		var i = 0, item;
		while {
			i < floatArray.size and: {
				item = this.next;
				item.notNil
			}
		} {
			floatArray[i] = item;
			i = i + 1;
		};
		if(i < floatArray.size) {
			floatArray.extend(i)
		};
	}
	writeData { |floatArray|
		collection = collection.addAll(floatArray);
	}
}

WavetablePrepGui : SCViewHolder {
	var <>model;
	var <wtPos = 0, <freq = 440, <sr = 44100;
	var <posSlider, <freqSlider, <graph;

	*new { |model, parent, bounds|
		^super.new.init(model, parent, bounds)
	}

	init { |argModel, parent, bounds|
		model = argModel;
		this.view = View(parent, bounds);
		view.layout = VLayout(
			HLayout(
				StaticText()
				.align_(\center).fixedWidth_(50).string_("wtPos"),
				posSlider = LayoutValueSlider(
					initValue: 0,
					spec: [0, model.tables.size - 1.001]
				)
			),
			HLayout(
				StaticText()
				.align_(\center).fixedWidth_(50).string_("freq"),
				freqSlider = LayoutValueSlider(
					initValue: 440,
					spec: [20, 20000, \exp]
				)
			),
			graph = MultiSliderView()
			.elasticMode_(true).drawRects_(false).drawLines_(true)
		);

		posSlider.action = { |view|
			this.wtPos = view.value;
			this.doAction;
		};
		freqSlider.action = { |view|
			this.freq = view.value;
			this.doAction;
		};

		// slightly ugly but SCViewHolder doesn't maintain a user close hook
		view.onClose = view.onClose.addFunc {
			this.changed(\didClose);
		};

		this.refresh;
	}

	wtPos_ { |argPos|
		wtPos = argPos;
		posSlider.value = wtPos;
		this.refresh;
		this.changed(\wtPos, wtPos);
	}
	freq_ { |argFreq|
		freq = argFreq;
		freqSlider.value = freq;
		this.changed(\freq, freq);
		this.refresh;
	}
	sr_ { |argSr|
		sr = argSr;
		this.changed(\sr, sr);
		this.refresh;
	}

	refresh {
		graph.value = model.blendAt(wtPos, freq, sr) * 0.5 + 0.5
	}
}

MultiWtOsc {
	*ar { |freq = 440, wtPos = 0, squeeze = 0, wtOffset = 0,
		bufnum = 0, wtSize = 2048, numTables = 8, ratio = 2,
		numOscs = 1, detune = 1, interpolation = 2, hardSync = 0, phaseMod = 0|

		var out = this.arOscs(freq, wtPos, squeeze, wtOffset,
			bufnum, wtSize, numTables, ratio,
			numOscs, detune, interpolation, hardSync, phaseMod
		);
		^out.asArray.sum
	}

	*arOscs { |freq = 440, wtPos = 0, squeeze = 0, wtOffset = 0,
		bufnum = 0, wtSize = 2048, numTables = 8, ratio = 2,
		numOscs = 1, detune = 1, interpolation = 2, hardSync = 0, phaseMod = 0|

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
		// I'm not sure about this actually --
		// it doesn't prevent clicks when modulating rapidly
		var lagPos = Lag.perform(wtPos.methodSelectorForRate, wtPos, 0.1);
		var evenWt = lagPos.round(2) * rowSize;
		var oddWt = ((lagPos + 1).round(2) - 1) * rowSize;
		// var wtXfade = lagPos.fold(0, 1) * 2 - 1;
		var wtXfade = SinOsc.perform(lagPos.methodSelectorForRate,
			0,
			// this should be -cos(lagPos * pi)
			// but SinOsc lookup table may be faster than 'cos' operator
			lagPos.fold(0, 1) * pi - 0.5pi
		);

		var normphase = Phasor.ar(hardSync, SampleDur.ir * (freq * detunes), 0, 1);
		// credit: Paul Miller of TXModular
		var phaseDist = (((normphase + phaseMod % 1.0) * 2 - 1) ** (2 ** squeeze)) * 0.5 + 0.5;
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

+ Color {
	luminance {
		^(0.2126 * red) + (0.7152 * green) + (0.0722 * blue)
	}
}
