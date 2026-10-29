package com.josh.learn

import android.content.Context

/**
 * Offline emoji ("symbol") picker for topics and lessons: a wide keyword map over the title (then description / topic),
 * inheriting a topic's emoji, and a deterministic fallback picked by a title hash from a varied set. Never the old
 * default star. No AI needed.
 */
object Symbols {
    /** The old "no match" default. Treated as "not chosen yet" unless Josh picked it himself. */
    const val OLD_DEFAULT = "⭐"

    /** Ordered: more specific words first. Each key is a regex alternation matched at a word start, case-insensitive. */
    private val rules: List<Pair<Regex, String>> = listOf(
        // vehicles & transport
        "motorbike|motorcycle|dirt ?bike" to "🏍️", "bicycle|cycling|mtb|mountain bik" to "🚲", "haldex|awd|4wd|4x4|cars?\\b|engine|mechanic|ute|utes\\b|truck|driv|gearbox|transmission|suspension|tyre|tire|brake|clutch|turbo" to "🚗",
        "trains?\\b(?! (your|the|a|my|it|to))|railway|locomotive" to "🚆", "plane|flying|fly\\b|aviation|pilot|aircraft|airline|jet" to "✈️", "boat|sail|kayak|yacht" to "⛵",
        "rocket|nasa|spacex|orbit|satellite|launch" to "🚀",
        // tech
        "crypto|bitcoin|blockchain|ethereum|nft" to "₿", "ai\\b|a\\.i\\.|llms?\\b|gpt|chatgpt|grok|machine learning|neural|deep learning|robot|automation" to "🤖",
        "cyber|security|hack|password|encrypt|forensic|privacy|malware|phishing" to "🔐", "network|wifi|wi-fi|router|server|homelab|home lab|linux|nas\\b|docker|kubernetes|cloud|dns|ip address|ethernet" to "🖥️",
        "code|coding|program|python|kotlin|java\\b|javascript|typescript|rust\\b|software|developer|app dev|android dev|git\\b|github|sql|database|algorithm|api\\b" to "💻",
        "phone|android|iphone|gadget|smartwatch|tablet|ar\\b|vr\\b|augmented|virtual reality|headset" to "📱", "3d print|printer|cnc|laser cut" to "🖨️",
        "electronic|circuit|arduino|raspberry|soldering|transistor|resistor|pcb" to "🔌", "chip|semiconductor|cpu|gpu|processor" to "💾",
        "internet|web\\b|website|browser|html|css" to "🌐", "camera|photo|lens|lightroom" to "📷", "video|youtube|edit(ing)? video|film|movie|cinema|documentar" to "🎬",
        // energy & physics
        "solar|panel|inverter" to "☀️", "battery|batteries|lithium|ev\\b|electric vehicle" to "🔋", "electric|electricity|wiring|voltage|power|energy|grid" to "⚡",
        "nuclear|fission|fusion|radiation|reactor" to "☢️", "quantum|physics|atom|particle|relativity|gravity|force|motion" to "⚛️", "magnet" to "🧲",
        // science & nature
        "black hole|galax|universe|cosmo|big bang" to "🌌", "space|planet|astronom|telescope|moon|mars|jupiter|sun\\b|solar system|star(s|gaz)" to "🪐",
        "dna|gene|genetic|crispr|cell\\b|cells\\b|evolution" to "🧬", "virus|bacteria|microb|vaccine|immun|pandemic|disease|infection" to "🦠",
        "chem|molecule|reaction|acid|element|periodic" to "🧪", "lab\\b|labs\\b|laboratory|instrument|microscope|pipett|spectro|centrifug|biolog|science" to "🔬",
        "volcano|earthquake|geolog|rock|mineral|fossil|dinosaur" to "🌋", "weather|climate|storm|cyclone|rain|cloud formation" to "⛅",
        "ocean|sea\\b|reef|beach|surf|tide|marine|shark|whale" to "🌊", "forest|tree|plant|garden|grow|botany|seed|compost" to "🌱",
        "dog|puppy|cat\\b|cats\\b|pet|animal|wildlife|zoo|bird|insect|bee\\b|bees\\b" to "🐾", "fish|fishing|angling" to "🎣", "horse|equestrian" to "🐎",
        "math|maths|algebra|calculus|geometry|number|statistic|probability|equation" to "➗",
        // health & body
        "brain|neuro|psych|mind|memory|habit|focus|adhd|mental|stress|anxiety|mood" to "🧠", "sleep|insomnia|dream" to "😴",
        "heart|cardio|blood pressure" to "❤️", "medic|doctor|nurse|anatomy|body|hospital|first aid|pharma|drug" to "🩺",
        "nutrition|diet|vitamin|protein|calorie|healthy eating" to "🥗", "health|fitness|gym|muscle|exercise|workout|strength|lifting|weights" to "💪",
        "run\\b|running|marathon|jogging" to "🏃", "yoga|meditat|mindful|breath" to "🧘", "tooth|teeth|dental" to "🦷",
        // food & drink
        "coffee|espresso|barista" to "☕", "tea\\b" to "🍵", "beer|brew|wine|whisk|cocktail|spirits" to "🍺", "pizza" to "🍕",
        "bread|sourdough|bak|cake|pastry" to "🍞", "cook|recipe|food|kitchen|chef|meal|cuisine" to "🍳", "bbq|barbecue|grill|smok" to "🔥",
        // money & work
        "crypto" to "₿", "stock|shares|etf|invest|dividend|portfolio|trading|market" to "📈", "money|finance|budget|tax|super\\b|superannuation|saving|loan|mortgage|bank|debt|econom|inflation|interest rate" to "💰",
        "business|startup|entrepreneur|marketing|sales|manag|leadership|career|job|interview|resume|trade(s)?\\b|pathway|apprentice" to "💼",
        "real estate|property|house|home buying|renovat|plumb" to "🏠", "law|legal|court|contract|rights" to "⚖️",
        "politic|government|election|parliament|democra|policy|vote" to "🏛️",
        // history & society
        "ancient|egypt|pyramid|pharaoh" to "🏺", "roman|greek|medieval|castle|knight|viking|empire" to "🏰", "war|ww1|ww2|world war|military|battle|army" to "🎖️",
        "history|historical|century|revolution" to "📜", "religio|philosoph|ethic|stoic|myth" to "🤔", "ufo|alien|uap|disclosure|extraterrestrial" to "🛸",
        "geograph|country|countries|map|travel|trip|world\\b|australia|aussie|japan trip|europe" to "🌏",
        // languages & words
        "japanese|japan" to "🇯🇵", "spanish|spain|mexic" to "🇪🇸", "french|france" to "🇫🇷", "german|germany" to "🇩🇪", "italian|italy" to "🇮🇹",
        "chinese|mandarin|china" to "🇨🇳", "korean|korea" to "🇰🇷", "language|grammar|vocab|words|speak|pronunc|sign language|linguist" to "🗣️",
        "book|read|novel|literature|writ|poem|poetry|story|author" to "📚", "speech|public speaking|debate|presentation" to "🎤",
        // arts, music & hobbies
        "guitar" to "🎸", "piano|keyboard" to "🎹", "drum" to "🥁", "sing|vocal|choir" to "🎤", "music|song|band|chord|melody|rhythm|dj\\b|produc(e|ing) music" to "🎵",
        "paint|draw|arts?\\b|sketch|illustrat|design|colour|color" to "🎨", "knit|sew|crochet|craft" to "🧶", "lego|model kit|miniature" to "🧱",
        "chess" to "♟️", "card game|poker|magic the gathering" to "🃏", "game|gaming|runescape|osrs|xbox|playstation|nintendo|steam deck|handheld|emulat|esport|minecraft" to "🎮",
        "camp|hike|hiking|bushwalk|outdoor|survival" to "🏕️", "climb|boulder" to "🧗", "swim" to "🏊",
        "sport|footy|afl|nrl|football|cricket|rugby|soccer|tennis|golf|basketball" to "🏉",
        // DIY & making
        "weld|fabricat|metalwork" to "🔧", "build|diy|wood|tool|carpent|repair|fix" to "🛠️", "car wash|detailing" to "🧽",
        "clean|laundry|organis" to "🧹", "parent|baby|kid|child" to "🧸", "relationship|dating|friend|social" to "💬",
    ).map { (k, e) -> Regex("\\b(?:$k)", RegexOption.IGNORE_CASE) to e }

    /** Varied fallback set (no star): chosen by a stable title hash so a lesson always gets the same one. */
    val fallbacks = listOf("📘", "📗", "📙", "📕", "🧭", "💡", "🔎", "🧩", "🎯", "🗺️", "🪄", "🔭", "🧠", "📝", "🎓", "🪁", "🧿", "🌀", "🔮", "🏷️")

    /** Country flags are more specific than the generic language/learning rules. */
    fun isFlag(e: String): Boolean = e.codePoints().allMatch { it in 0x1F1E6..0x1F1FF }

    fun match(text: String?): String? = text?.takeIf { it.isNotBlank() }?.let { t -> rules.firstOrNull { it.first.containsMatchIn(t) }?.second }

    fun fallback(seed: String): String {
        var h = 1125899906842597L // stable across runs/devices (String.hashCode is too, but spread it out)
        seed.trim().lowercase().forEach { h = 31 * h + it.code }
        h = h xor (h ushr 29); h *= -0x4b47d5b1a5b6f6c7L; h = h xor (h ushr 32)
        return fallbacks[((h % fallbacks.size) + fallbacks.size).toInt() % fallbacks.size]
    }

    /** A topic: its name, then description, then the hash fallback. */
    fun forTopic(title: String, description: String = ""): String = match(title) ?: match(description) ?: fallback(title)

    fun isDefault(e: String?) = e.isNullOrBlank() || e.trim() == OLD_DEFAULT

    /**
     * A lesson: a keyword in its title, else the topic's own emoji (built-in or his own topic), else a keyword in the
     * topic text, else the hash fallback.
     */
    fun forLesson(title: String, topic: String, topicEmoji: String?): String =
        match(title) ?: topicEmoji?.takeUnless { isDefault(it) } ?: match(topic) ?: fallback(title.ifBlank { topic })

    fun forLesson(l: Lesson): String = forLesson(l.title, l.topic, Topics.category(l.category)?.emoji)

    /** What a lesson shows everywhere. */
    fun of(l: Lesson): String = l.emoji?.takeUnless { isDefault(it) && !l.emojiPicked } ?: forLesson(l)

    /** Picker choices: the suggestion first, then a broad set (the star stays available if he wants it). */
    fun choices(suggestion: String): List<String> = (listOf(suggestion) + Topics.emojiChoices + fallbacks).distinct()

    /**
     * One-time migration (on update): gives saved lessons and his own topics a fitting symbol instead of the star.
     * Never touches a symbol Josh picked himself. Returns how many were changed.
     */
    fun migrate(c: Context): Int {
        val prefs = LearnApp.prefs(c)
        if (prefs.getInt(KEY, 0) >= VERSION) return 0
        var n = Topics.migrateCustomEmoji(c)
        Repo.lessons.forEach { l ->
            if (!l.emojiPicked && isDefault(l.emoji)) { l.emoji = forLesson(l); n++ }
        }
        if (n > 0) Repo.save()
        prefs.edit().putInt(KEY, VERSION).apply()
        return n
    }
    private const val KEY = "symbols_migration"; private const val VERSION = 1
}
