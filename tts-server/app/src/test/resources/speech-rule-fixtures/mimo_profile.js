let SpeechRuleJS = {
    name: "MiMo speech profile fixture",
    id: "tts-server.mimo-profile-fixture",
    author: "tts-server tests",
    version: 1,
    tags: {narration: "旁白"},

    getTagName(tag, tagMap) {
        return tagMap[tag] || tag;
    },

    handleText(text) {
        return [{
            text: text,
            tag: "narration",
            id: 101,
            profile: {
                schemaVersion: 1,
                voice: "冰糖",
                style: "温柔地朗读"
            },
            baseInstructions: ["保持自然停顿"]
        }];
    },

    splitText(text) {
        return [text];
    }
};
