// Test-only: use a real requester so completion and final delivery can be asserted through a nexus.
function initializeCoreMod() {
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    return { neoeco_gto_acceptance_requester: {
        target: {type:'METHOD', 'class':'cn.dancingsnow.neoecoae.client.diagnostics.GTOCompatibilityClientProbe',
            methodName:'verify',methodDesc:'(Lnet/minecraft/server/level/ServerLevel;)V'},
        transformer:function(method) {
            var calls=[];
            for(var node=method.instructions.getFirst();node!==null;node=node.getNext()) {
                if(node.getOpcode()===185 && node.owner==='appeng/api/networking/crafting/ICraftingService'
                    && node.name==='submitJob') calls.push(node);
            }
            if(calls.length!==1) throw new Error('Acceptance submit contract changed: '+calls.length);
            var call=calls[0];
            method.instructions.set(call,new MethodInsnNode(184,
                'com/moakiee/thunderbolt/neoecoqa/NeoEcoGtoRequesterProbe','submit',
                '(Lappeng/api/networking/crafting/ICraftingService;'+call.desc.substring(1),false));
            return method;
        }
    }};
}
