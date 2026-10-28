// Keep GTUtil's physical-side guard; replace only its client GLFW query.
function initializeCoreMod() {
    var methods = ['isShiftDown', 'isCtrlDown', 'isAltDown'];
    var definitions = {};
    methods.forEach(function(name) {
        definitions['gto_modifier_' + name] = {
            target: {
                type: 'METHOD',
                'class': 'com.gregtechceu.gtceu.utils.GTUtil',
                methodName: name,
                methodDesc: '()Z'
            },
            transformer: function(method) {
                if (method.name !== name || method.desc !== '()Z' || (method.access & 8) === 0) {
                    throw new Error('GTO modifier-key method changed: ' + name);
                }
                var replaced = 0;
                for (var instruction = method.instructions.getFirst(); instruction !== null; instruction = instruction.getNext()) {
                    if (instruction.getOpcode() === 184
                            && instruction.owner === 'net/minecraft/client/gui/screens/Screen'
                            && instruction.desc === '()Z') {
                        instruction.owner = 'com/moakiee/thunderbolt/compat/gto/ClientModifierKeys';
                        instruction.name = name;
                        replaced++;
                    }
                }
                if (replaced !== 1) throw new Error('Expected one GTO client key query: ' + name + ', found ' + replaced);
                return method;
            }
        };
    });
    return definitions;
}
