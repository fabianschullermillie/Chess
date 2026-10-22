// Runs the Wasm engine off the main thread. Messages in: UCI command strings. Messages out: engine output lines.
// The engine's search threads are pthreads: nested workers that Emscripten starts from this same script,
// so in those only wombat.js is loaded (it bootstraps the pthread by itself).
importScripts('wombat.js');

if (!self.name.startsWith('em-pthread')) {
    let engine = null;
    const pending = [];
    const send = cmd => engine.ccall('wombat_command', null, ['string'], [cmd]);

    createWombat({
        print: line => postMessage(line),
        printErr: line => postMessage('info string ' + line),
    }).then(m => {
        engine = m;
        pending.splice(0).forEach(send);
    }, err => postMessage('wombat error ' + err));

    onmessage = e => engine ? send(e.data) : pending.push(e.data);
}
