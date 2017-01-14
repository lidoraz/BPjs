/* global bp, noEvents, emptySet */

var in1a = bp.Event("in1a");
var in1b = bp.Event("in1b");
var ext1 = bp.Event("ext1");

bp.registerBThread("In1", function() {
    bsync( {request:in1a} );
    bsync( {waitFor:ext1} );
    bsync( {request:in1b} );
});
