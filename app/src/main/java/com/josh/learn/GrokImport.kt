package com.josh.learn

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlin.math.exp

/**
 * Turns a Grok data-export ZIP into ranked "Suggested for you" topics, fully on-device (no network).
 * Streams the ZIP and the JSON inside it (never loads the whole export), keeps only the user's own
 * messages, titles and timestamps for the duration of the scan, skips personal/sensitive chats, and
 * returns only aggregate topics (raw text is discarded).
 */
object GrokImport {
    class Cancelled : Exception("Cancelled")

    data class Topic(
        val title: String, val category: String, val emoji: String, val score: Double, val chats: Int, val messages: Int,
        val days: Int, val subtopics: List<String>,
    )
    data class Result(val topics: List<Topic>, val chats: Int, val messages: Int, val excludedSensitive: Int, val unmatched: Int)

    // ---------------------------------------------------------------- taxonomy
    class Def(val title: String, val category: String, val emoji: String, kw: String, val subs: List<Pair<String, String>>) {
        val re = Regex(kw + "\\b", RegexOption.IGNORE_CASE)
        val subRes = subs.map { it.first to Regex(it.second, RegexOption.IGNORE_CASE) }
    }
    private fun d(title: String, cat: String, emoji: String, kw: String, vararg subs: Pair<String, String>) = Def(title, cat, emoji, kw, subs.toList())

    val taxonomy: List<Def> = listOf(
        d("4WD & Car Mechanics", "Cars", "🚗",
            "\\b(land ?cruiser|lc ?100|1fz|4wd|4x4|awd|haldex|golf r|mk7|subaru|forester|outback|ee20|diesel|dpf|egr|coolant|gearbox|transmission|clutch|brake|caliper|rotor|suspension|lift kit|shock absorber|coilover|sway bar|steering|serpentine|tensioner|alternator|radiator|engine oil|5w-?30|5w-?40|10w-?40|viscosity|oil change|spark plug|injector|turbo|intercooler|exhaust|downpipe|tyre|tire|wheel|diff(erential)?|ute|navara|hilux|ranger|everest|audi|volkswagen|vw|skoda|bmw|mazda|toyota|vacuum leak|rough idle|idle|check engine|obd|vcds|car battery|mechanic|towing|off-?road|winch|snorkel|engine|car)",
            "How AWD systems like Haldex work" to "haldex|awd|4wd|4x4", "Engine oils: viscosity, specs and additives" to "oil|viscosity|5w|10w",
            "Diesel DPF and EGR explained" to "dpf|egr|diesel|regen", "Suspension, lifts and handling basics" to "lift|suspension|shock|coilover|sway",
            "Diagnosing faults with OBD scanners" to "obd|vcds|check engine|fault|code", "Cooling systems and coolant types" to "coolant|radiator|overheat",
            "Brakes: pads, rotors and calipers" to "brake|caliper|rotor", "Vacuum leaks and rough idle" to "vacuum|idle"),
        d("Lab Instruments & DNA Forensics", "Science", "🧬",
            "\\b(capillary|electrophoresis|3500|genetic analy[sz]er|sequencer|pcr|qpcr|quantstudio|thermocycler|dna|str kits?|identifiler|globalfiler|forensic|ftir|spectro\\w*|mp-?aes|icp|hplc|gc-?ms|chromatograph\\w*|mass spec|centrifuge|pipette|reagent|lab water|milli-?q|purge gas|nitrogen generator|instrument|calibrat\\w*|laborator\\w*|agilent|thermo ?fisher|applied biosystems|polymer|buffer)",
            "How capillary electrophoresis DNA analysers work" to "capillary|electrophoresis|3500|polymer", "PCR and real-time qPCR basics" to "pcr|quantstudio|thermocycler",
            "Forensic STR profiling explained" to "str|identifiler|globalfiler|forensic", "FTIR and spectroscopy for beginners" to "ftir|spectro|mp-?aes|icp",
            "Chromatography (HPLC, GC-MS) basics" to "hplc|gc-?ms|chromatograph", "Lab water, gases and instrument upkeep" to "water|purge|nitrogen|calibrat"),
        d("Gaming & Handhelds", "Gaming", "🎮",
            "\\b(osrs|runescape|old school|steam ?deck|steamos|steam|gaming|games?|xbox|playstation|ps5|nintendo|anbernic|retroid|emulat\\w*|retro handheld|handheld|sonic|minecraft|grand exchange|quests?|boss|raids?|skilling)",
            "Old School RuneScape: efficient early game" to "osrs|runescape|old school|quest|skilling", "Steam Deck and SteamOS tips" to "steam",
            "Retro handhelds and emulation compared" to "anbernic|retroid|emulat|retro|handheld", "How in-game economies and flipping work" to "grand exchange|flip|gp|economy",
            "Classic game history" to "sonic|nintendo|sega|classic"),
        d("Australian & World Politics", "Politics", "🏛️",
            "\\b(politic\\w*|elections?|vot(e|ing)|labor|liberals?|greens|one nation|albanese|dutton|parliament|senate|prime minister|trump|biden|harris|democrats?|republicans?|congress|aukus|tariffs?|government|ministers?|referendum|immigration|geopolit\\w*|china|russia|ukraine|israel|gaza|nato|polls?)",
            "How Australian federal elections work" to "election|vote|voting|preferential|senate", "What AUKUS means for Australia" to "aukus|submarine|defence",
            "Tariffs, interest rates and everyday prices" to "tariff|inflation|price", "Comparing Australian and US political systems" to "trump|congress|democrat|republican|us ",
            "Reading polls without getting misled" to "poll", "Geopolitics basics: China, Russia and alliances" to "china|russia|ukraine|nato|geopolit"),
        d("Home Lab & Networking", "Tech", "🖥️",
            "\\b(proxmox|docker|portainer|jellyfin|plex|nas|truenas|unraid|home ?lab|servers?|virtual machines?|vms?|routers?|mesh|deco|wi-?fi|ethernet|vlan|dns|dhcp|ip address|port forward\\w*|cloudflare|tunnel|tailscale|pi-?hole|raspberry pi|esp32|arduino|linux|ubuntu|windows|ltsc|powershell|bios|ssd|nvme|pc build|motherboard|network\\w*)",
            "Proxmox and virtualisation for beginners" to "proxmox|vm|virtual", "Docker self-hosting: Jellyfin, Portainer and tunnels" to "docker|jellyfin|portainer|cloudflare|tunnel",
            "Home mesh Wi-Fi explained" to "mesh|deco|wi-?fi|router", "ESP32 and microcontroller projects" to "esp32|arduino|raspberry",
            "Hardening a home Windows PC" to "windows|ltsc|powershell|bios", "DNS, DHCP and home network basics" to "dns|dhcp|ip address|vlan|port"),
        d("Health Science & Human Biology", "Health", "💪",
            "\\b(creatine|magnesium|supplements?|vitamins?|protein|sleep|caffeine|circadian|metabol\\w*|hormones?|endocrine|testosterone|immune|vaccines?|mrna|gut|microbiome|nutrition|diet|fasting|calories?|muscles?|exercise|workout|gym|cardio|cholesterol|genome|biology|anatomy)",
            "Creatine, magnesium and common supplements" to "creatine|magnesium|supplement|vitamin", "How mRNA vaccines work" to "mrna|vaccine|immune",
            "Sleep, caffeine and circadian rhythm" to "sleep|caffeine|circadian", "Hormones and the endocrine system" to "hormone|endocrine|testosterone",
            "Nutrition and metabolism basics" to "nutrition|diet|calorie|metabol|fasting", "How muscles grow with exercise" to "muscle|gym|workout|exercise"),
        d("Space, Sun & Astronomy", "Science", "🌌",
            "\\b(space|nasa|spacex|starship|rockets?|orbit\\w*|satellites?|asteroids?|comets?|3i/atlas|interstellar|solar wind|solar flares?|sunspots?|space weather|aurora|planets?|mars|moon|jupiter|galax\\w*|black holes?|telescopes?|jwst|astronom\\w*|cosmos|universe|earthquakes?|volcano\\w*|tectonic)",
            "Interstellar visitors and comets" to "interstellar|3i|comet|asteroid", "Solar wind, flares and space weather" to "solar|flare|sunspot|aurora|space weather",
            "Mars missions and the search for life" to "mars|life", "How Starship and orbital refuelling work" to "starship|spacex|rocket|orbit",
            "Earthquakes, volcanoes and plate tectonics" to "earthquake|volcano|tectonic", "Black holes and galaxies" to "black hole|galax|universe"),
        d("Cryptocurrency & Blockchain", "Money", "₿",
            "\\b(bitcoin|btc|bsv|teranode|satoshi|blockchain|crypto\\w*|tether|usdt|stablecoins?|ethereum|eth|solana|xrp|altcoins?|mining|hash ?rate|halving|defi|nfts?|lightning network|tps|coinbase|binance)",
            "Bitcoin basics: blocks, fees and security" to "bitcoin|btc|block|fee", "What TPS and blockchain scaling mean" to "tps|scal|teranode|bsv",
            "Stablecoins explained" to "tether|usdt|stablecoin", "Crypto tax basics in Australia" to "tax|ato", "How crypto mining works" to "mining|hash|halving"),
        d("Investing & Aussie Money", "Money", "💰",
            "\\b(invest\\w*|shares|stocks?|etfs?|index funds?|dividends?|portfolio|super(annuation)?|hecs|interest rates?|rba|mortgages?|loans?|savings|budget\\w*|tax|ato|negative gearing|property|house prices?|rent|brokerage|commsec|vanguard|inflation)",
            "How Australian superannuation works" to "super", "Building a simple ETF portfolio" to "etf|index|portfolio|vanguard",
            "HECS-HELP and student debt" to "hecs", "RBA interest rates and your mortgage" to "rba|interest|mortgage|loan", "Shares, dividends and brokerage basics" to "share|stock|dividend|broker"),
        d("Phones, AR & Gadgets", "Tech", "📱",
            "\\b(phones?|iphone|pixel|samsung|android|ios|smartwatch|earbuds|headphones|ar glasses|vr|meta quest|xreal|cameras?|snapdragon|soc|battery life|chargers?|usb-?c|tablets?|ipad|laptops?|android auto|carplay|gadgets?)",
            "How phone chips and cameras differ" to "snapdragon|soc|camera|chip", "AR glasses: what they can and can't do" to "ar glasses|xreal|vr|quest",
            "Android Auto and phone-car integration" to "android auto|carplay", "Battery tech, charging and phone thermals" to "battery|charg|usb", "Choosing a phone upgrade wisely" to "iphone|pixel|samsung|phone"),
        d("Career & Trades Pathways", "Career", "💼",
            "\\b(careers?|jobs?|resume|cv|interviews?|pay rise|salary|promotion|apprentice\\w*|trades?|electrician|electrical licen[cs]e|restricted licen[cs]e|qualifications?|certificate|tafe|degree|university|field service|technician|employer|security clearance|nv1|nv2|baseline clearance|recruiter|linkedin)",
            "Technician vs field service careers" to "technician|field service", "Negotiating a pay rise with evidence" to "pay rise|salary|promotion",
            "Trade licences and apprenticeships" to "electric|licen|apprentice|trade|tafe", "Security clearances explained" to "clearance|nv1|nv2|baseline", "Writing a strong technical CV" to "cv|resume|interview"),
        d("Physics & Energy Ideas", "Science", "⚛️",
            "\\b(physics|quantum|relativity|gauge|particles?|electrons?|photons?|field theory|plasma|fusion|fission|nuclear|uranium|uf6|reactors?|tesla coil|nikola tesla|thermodynamic\\w*|entropy|electromagnet\\w*|resonance|antimatter|higgs)",
            "Gauge theory in plain English" to "gauge|field theory", "Nuclear fuel: from uranium to reactors" to "uranium|uf6|nuclear|fission|reactor",
            "Plasma and fusion basics" to "plasma|fusion", "Quantum physics basics" to "quantum", "Tesla's ideas vs modern physics" to "tesla|resonance|electromagnet"),
        d("UFOs, UAPs & Disclosure", "Science", "🛸",
            "\\b(ufos?|uaps?|aliens?|extraterrestrial|disclosure|grusch|roswell|blue book|abduct\\w*|remote viewing|paranormal|ancient aliens?|tic tac)",
            "History of UFO investigations" to "blue book|roswell|history", "What the UAP hearings actually said" to "uap|hearing|grusch|congress|disclosure",
            "Remote viewing and consciousness research" to "remote viewing|conscious", "Separating evidence from speculation" to "evidence|proof|real", "Space situational awareness and satellites" to "satellite|radar"),
        d("Digital Forensics & Privacy", "Tech", "🔐",
            "\\b(privacy|forensic tools?|cellebrite|graykey|mobile forensics|data recovery|metadata|exif|steganograph\\w*|encrypt\\w*|vpns?|tor|tracking|spyware|malware|antivirus|hack\\w*|cyber\\w*|phishing|scams?|incognito|digital footprint)",
            "What mobile forensic tools actually do" to "cellebrite|graykey|forensic|recovery", "How VPNs protect (and don't protect) you" to "vpn|tor|tracking",
            "Steganography: hiding data in files" to "stegan", "Metadata and what it reveals" to "metadata|exif", "Spotting scams, phishing and malware" to "scam|phishing|malware|spyware|hack"),
        d("Home Energy & Solar", "Lifestyle", "🔋",
            "\\b(solar|inverters?|battery storage|home battery|powerwall|kwh|electricity bill|power bill|time of use|amber|feed-?in|portable power station|ecoflow|jackery|bluetti|12v|lifepo4|deep cycle|dc-dc|generators?|smart meters?|off-?grid|heat pump|air ?con\\w*)",
            "Solar and battery payback in Australia" to "solar|battery|powerwall|payback", "Portable power stations compared" to "ecoflow|jackery|bluetti|portable",
            "Time-of-use and wholesale electricity plans" to "time of use|amber|tariff|bill", "Sizing a 12V battery system" to "12v|lifepo4|deep cycle|dc-dc", "Smart meters and what they change" to "smart meter|meter"),
        d("History's Odd Corners", "History", "📜",
            "\\b(history of|historical|ancient|medieval|empires?|roman|egypt\\w*|pyramids?|babel|biblical|world war|ww2|wwii|ww1|cold war|uss liberty|antarctica|megalith\\w*|stonehenge|civili[sz]ations?|archaeolog\\w*|dynast\\w*|myths?)",
            "Ancient myths and their history" to "babel|myth|biblical|ancient", "Why Antarctica is protected" to "antarctica", "Little-known incidents of the 20th century" to "uss liberty|cold war|world war|ww",
            "Megalithic building theories" to "megalith|pyramid|stonehenge", "Overlooked moments that shaped the world" to "history|empire|civili"),
        d("How AI & LLMs Work", "Tech", "🤖",
            "\\b(ai|artificial intelligence|llms?|gpt-?\\d\\w*|openai|anthropic|machine learning|neural net\\w*|transformer models?|training data|ai alignment|agi|language models?)",
            "Tokens and transformers in plain English" to "token|transformer|gpt|llm", "How training data shapes model behaviour" to "training|data|bias",
            "AI safety and alignment basics" to "alignment|safety|agi", "Cloud AI vs on-device models" to "local|offline|device|cloud", "How AI chatbots compare" to "grok|chatgpt|claude|gemini"),
        d("Learning to Fly", "Career", "✈️",
            "\\b(pilots?|aviation|aircraft|planes?|flying|flight training|rpl|rpc|ppl|casa|cessna|solo flight|airspace|flight plans?|avgas|helicopters?)",
            "Pilot licences in Australia (RPC, RPL, PPL)" to "rpl|rpc|ppl|licen", "Hours and costs to first solo" to "solo|hours|cost", "Aviation medicals explained" to "medical|class 2",
            "How light aircraft systems work" to "fuel|engine|cessna|aircraft", "Reading a basic flight plan" to "flight plan|airspace|navigation"),
        d("ECU Tuning & Car Electronics", "Cars", "🔧",
            "\\b(ecu|remap\\w*|tun(e|ed|ing)|winols|ecm titanium|dyno|boost|immobili[sz]\\w*|key programming|autel|km100|xenon|hid|ballasts?|headlight modules?|can ?bus|wiring loom|fuse box|relay)",
            "What an ECU remap actually changes" to "ecu|remap|tun|boost|dyno", "ECU tuning software overview" to "winols|ecm titanium|software",
            "Car immobilisers and key programming" to "immobili|key|autel|km100", "Xenon headlights and ballasts" to "xenon|hid|ballast|headlight", "Safe tuning limits for daily drivers" to "safe|limit|daily"),
        d("Psychology & Personality", "Health", "🧠",
            "\\b(psycholog\\w*|jung\\w*|individuation|archetypes?|myers|mbti|personality|introvert|extrovert|cognitive bias\\w*|habits?|motivation|willpower|dopamine|mindset|emotions?|behaviou?r|philosoph\\w*|stoic\\w*|meditation)",
            "Jung's individuation in everyday terms" to "jung|individuation|archetype", "Myers-Briggs: useful or oversold?" to "myers|mbti|personality|introvert",
            "Cognitive biases that fool everyone" to "bias", "Habit formation and willpower" to "habit|willpower|motivation|dopamine", "Stoicism and practical philosophy" to "stoic|philosoph|meditation"),
        // ---- generic fallbacks
        d("Coding & Spreadsheets", "Tech", "💻",
            "\\b(python|javascript|kotlin|java|source code|coding|programming|scripts?|regex|sql|excel|spreadsheets?|formulas?|github|git|html|css|website)",
            "Python basics" to "python|script", "Spreadsheet formulas that save time" to "excel|spreadsheet|formula", "How websites work (HTML, CSS, JS)" to "html|css|javascript|website",
            "Git and GitHub basics" to "git", "SQL and databases" to "sql|database"),
        d("Cooking & Food", "Lifestyle", "🍳",
            "\\b(recipes?|cook\\w*|bak(e|ing)|meals?|kitchen|bbq|coffee|beer|wine|food)",
            "Cooking fundamentals" to "cook|recipe|meal", "The science of baking" to "bak", "Coffee: beans to brew" to "coffee", "Barbecue and smoking meat" to "bbq|smok", "Food science basics" to "food"),
        d("Travel & Places", "Lifestyle", "🧳",
            "\\b(travel\\w*|trips?|holidays?|visas?|passport|hotels?|itinerar\\w*|japan|bali|europe)",
            "Planning a trip on a budget" to "budget|trip|plan", "Visas and travel documents" to "visa|passport", "Japan travel basics" to "japan", "Getting around Europe" to "europe", "Jet lag and long flights" to "flight|jet"),
        d("DIY & Home Repairs", "Lifestyle", "🔨",
            "\\b(diy|drill\\w*|screws?|timber|paint\\w*|plaster\\w*|plumbing|leaks?|garden\\w*|lawn|sheds?|weld\\w*|tools?)",
            "Power tools basics" to "drill|tool", "Painting and plastering like a pro" to "paint|plaster", "Basic home plumbing" to "plumb|leak|tap", "Garden and lawn care" to "garden|lawn", "Welding basics" to "weld"),
        d("Nature & Animals", "Science", "🌿",
            "\\b(animals?|birds?|dogs?|cats?|snakes?|spiders?|plants?|trees?|weather|storms?|cyclones?|ocean|reef)",
            "Australian wildlife" to "snake|spider|bird|animal", "How storms and cyclones form" to "storm|cyclone|weather", "Coral reefs" to "reef|ocean", "Dog and cat behaviour" to "dog|cat", "How plants grow" to "plant|tree"),
        d("Maths & Numbers", "Science", "📐",
            "\\b(maths?|equations?|algebra|calculus|statistic\\w*|probabilit\\w*|geometry|percent\\w*)",
            "Statistics without the jargon" to "statistic|probabil", "Algebra refresher" to "algebra|equation", "Calculus intuition" to "calculus", "Percentages and everyday maths" to "percent", "Geometry basics" to "geometry"),
        d("Music, Film & Books", "Arts", "🎬",
            "\\b(music|songs?|albums?|guitar|movies?|films?|tv shows?|netflix|anime|books?|novels?)",
            "Music theory basics" to "music|song|guitar", "How films are made" to "movie|film", "Great books and why they last" to "book|novel", "TV storytelling" to "tv|netflix|series", "Anime history" to "anime"),
    )

    // ---------------------------------------------------------------- sensitive filter (conservative: any hit = skip the whole chat)
    val sensitive = Regex(listOf(
        // relationships & intimacy
        "girlfriend", "boyfriend", "my wife", "my husband", "my partner", "my ex\\b", "breakup", "break up", "broke up", "dating", "tinder", "hinge app",
        "relationship", "divorce", "custody", "marriage", "cheat(ing|ed) on", "\\bsex", "nude", "porn", "crush on", "in love",
        // individual medical / mental health
        "diagnos", "prescri", "my doctor", "\\bmy gp\\b", "medication", "my meds", "dosage", "side effects", "my symptoms", "surgery", "hospital",
        "blood test", "biopsy", "cancer", "tumou?r", "\\bstds?\\b", "\\bstis?\\b", "pregnan", "miscarriage", "therapist", "therapy", "psychiatr", "counsell",
        "depress", "suicid", "self-?harm", "panic attack", "anxiety", "mental health", "rehab", "addiction", "withdrawal", "adhd", "\\bthc\\b", "cannabis", "dosing", "\\bdrugs?\\b", "steroid",
        // credentials & identifiers
        "password", "passcode", "passphrase", "seed phrase", "recovery phrase", "private key", "api key", "\\b2fa\\b", "\\botp\\b", "login details",
        "\\bpin\\b", "credit card", "card number", "bank account", "\\bbsb\\b", "account number", "tax file number", "\\btfn\\b", "medicare number",
        "licen[cs]e number", "passport number", "\\bssn\\b",
        // legal / personal trouble
        "lawyer", "solicitor", "illegal", "deauth", "\\bcourt\\b", "police", "arrest", "charged with", "centrelink", "debt collector", "bankrupt", "eviction",
    ).joinToString("|"), RegexOption.IGNORE_CASE)

    fun isSensitive(text: String) = sensitive.containsMatchIn(text)

    // ---------------------------------------------------------------- scoring
    private class Acc {
        var chats = 0; var messages = 0; val days = HashSet<Long>(); var recency = 0.0
        val subHits = HashMap<String, Int>(); var bestTitle: String? = null; var bestTitleMsgs = 0
    }

    private class Conv { var id: String? = null; var title = ""; var created = 0L; val human = ArrayList<String>(); val times = ArrayList<Long>() }

    private const val MAX_MSG = 4_000
    private const val MAX_CONV_TEXT = 40_000

    /** Detects a ZIP by its magic bytes. */
    fun isZip(head: ByteArray) = head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

    /**
     * Streams [zip] (a Grok export) and returns ranked topics. [onProgress] gets the number of chats read so far.
     * Nothing is written to disk.
     */
    fun analyseZip(zip: InputStream, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(),
                   onProgress: (Int) -> Unit = {}, cancelled: () -> Boolean = { false }): Result {
        val state = Scan(now, zone, onProgress, cancelled)
        ZipInputStream(zip.buffered(1 shl 16)).use { z ->
            while (true) {
                if (cancelled()) throw Cancelled()
                val e = z.nextEntry ?: break
                val n = e.name.lowercase()
                if (e.isDirectory || !n.endsWith(".json")) continue
                // conversations live in e.g. ttl/30d/export_data/<uid>/prod-grok-backend.json; be tolerant and scan any JSON
                runCatching { state.readJson(NoCloseStream(z)) }.onFailure { if (it is Cancelled) throw it }
            }
        }
        return state.result()
    }

    /** Same as [analyseZip] for a bare export JSON. */
    fun analyseJson(json: InputStream, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(),
                    onProgress: (Int) -> Unit = {}, cancelled: () -> Boolean = { false }): Result =
        Scan(now, zone, onProgress, cancelled).apply { readJson(json) }.result()

    private class NoCloseStream(val s: InputStream) : java.io.FilterInputStream(s) { override fun close() {} }

    private class Scan(val now: Long, val zone: ZoneId, val onProgress: (Int) -> Unit, val cancelled: () -> Boolean) {
        val acc = HashMap<Def, Acc>(); val seen = HashSet<String>()
        var chats = 0; var messages = 0; var excluded = 0; var unmatched = 0

        fun readJson(input: InputStream) {
            val r = JsonReader(InputStreamReader(input, Charsets.UTF_8)); r.isLenient = true
            when (r.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        val name = r.nextName()
                        if ((name == "conversations" || name == "chats") && r.peek() == JsonToken.BEGIN_ARRAY) readConvs(r) else r.skipValue()
                    }
                    r.endObject()
                }
                JsonToken.BEGIN_ARRAY -> readConvs(r)
                else -> r.skipValue()
            }
        }

        fun readConvs(r: JsonReader) {
            r.beginArray()
            while (r.hasNext()) {
                if (cancelled()) throw Cancelled()
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                val c = Conv(); readConv(r, c); add(c)
            }
            r.endArray()
        }

        fun readConv(r: JsonReader, c: Conv) {
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "conversation" -> if (r.peek() == JsonToken.BEGIN_OBJECT) readMeta(r, c) else r.skipValue()
                    "title" -> c.title = str(r) ?: c.title
                    "id", "conversation_id" -> c.id = str(r) ?: c.id
                    "create_time", "created_at" -> c.created = time(r).takeIf { it > 0 } ?: c.created
                    "responses", "messages", "chat_messages" -> if (r.peek() == JsonToken.BEGIN_ARRAY) readMsgs(r, c) else r.skipValue()
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }

        fun readMeta(r: JsonReader, c: Conv) {
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "title" -> c.title = str(r) ?: c.title
                "id" -> c.id = str(r) ?: c.id
                "create_time", "created_at" -> c.created = time(r)
                else -> r.skipValue()
            }
            r.endObject()
        }

        fun readMsgs(r: JsonReader, c: Conv) {
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                r.beginObject()
                var text: String? = null; var sender: String? = null; var t = 0L
                fun field(name: String) {
                    when (name) {
                        "message", "text", "content", "query" -> { val s = if (r.peek() == JsonToken.STRING) r.nextString() else { r.skipValue(); null }
                            if (s != null && (text == null || name == "message")) text = s.take(MAX_MSG) }
                        "sender", "role", "author" -> sender = str(r)
                        "create_time", "created_at", "timestamp" -> t = time(r)
                        else -> r.skipValue()
                    }
                }
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (name == "response" && r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject(); while (r.hasNext()) field(r.nextName()); r.endObject()
                    } else field(name)
                }
                r.endObject()
                val s = sender?.lowercase()
                if ((s == "human" || s == "user") && !text.isNullOrBlank()) { c.human += text!!; if (t > 0) c.times += t }
            }
            r.endArray()
        }

        fun str(r: JsonReader): String? = if (r.peek() == JsonToken.STRING || r.peek() == JsonToken.NUMBER) r.nextString() else { r.skipValue(); null }

        /** ISO string, epoch number (s or ms), or Mongo-style {"$date":{"$numberLong":"…"}} / {"$date":"…"}. */
        fun time(r: JsonReader): Long = when (r.peek()) {
            JsonToken.STRING -> parseTime(r.nextString())
            JsonToken.NUMBER -> parseTime(r.nextString())
            JsonToken.BEGIN_OBJECT -> { var v = 0L; r.beginObject(); while (r.hasNext()) { r.nextName(); val x = time(r); if (x > 0) v = x }; r.endObject(); v }
            else -> { r.skipValue(); 0L }
        }

        fun add(c: Conv) {
            val id = c.id; if (id != null && !seen.add(id)) return
            if (c.human.isEmpty()) return
            chats++; if (chats % 25 == 0) onProgress(chats)
            val text = buildString {
                repeat(3) { append(c.title).append('\n') }
                for (h in c.human) { if (length > MAX_CONV_TEXT) break; append(h).append('\n') }
            }
            if (isSensitive(text)) { excluded++; return }
            messages += c.human.size
            val hits = taxonomy.map { it to it.re.findAll(text).take(200).count() }.filter { it.second > 0 }.sortedByDescending { it.second }
            val top = hits.firstOrNull()
            if (top == null || top.second < 2) { unmatched++; return }
            val assigned = hits.filter { it.second >= top.second * 0.7 }.take(2).map { it.first }
            val times = c.times.ifEmpty { listOf(c.created) }.filter { it > 0 }
            val last = times.maxOrNull() ?: c.created
            for (dfn in assigned) {
                val a = acc.getOrPut(dfn) { Acc() }
                a.chats++; a.messages += c.human.size
                times.forEach { a.days += Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
                if (last > 0) a.recency += exp(-((now - last).coerceAtLeast(0) / 86_400_000.0) / 180.0)
                dfn.subRes.forEach { (s, re) -> val n = re.findAll(text).take(50).count(); if (n > 0) a.subHits[s] = (a.subHits[s] ?: 0) + n }
                val tt = c.title.trim()
                if (tt.isNotBlank() && dfn.re.containsMatchIn(tt) && !tt.contains("conversation", true) && c.human.size > a.bestTitleMsgs) { a.bestTitleMsgs = c.human.size; a.bestTitle = tt }
            }
        }

        fun result(): Result {
            onProgress(chats)
            val topics = acc.entries.map { (dfn, a) ->
                val score = 3.0 * a.chats + 1.0 * a.messages + 4.0 * a.days.size + 20.0 * a.recency
                val ranked = dfn.subs.map { it.first }.sortedByDescending { a.subHits[it] ?: 0 }
                val subs = (listOfNotNull(a.bestTitle?.takeIf { a.chats >= 3 && it.length in 8..70 }) + ranked).distinctBy { it.lowercase() }.take(5)
                Topic(dfn.title, dfn.category, dfn.emoji, Math.round(score * 10) / 10.0, a.chats, a.messages, a.days.size, subs)
            }.filter { it.chats >= 2 }.sortedByDescending { it.score }.take(20)
            return Result(topics, chats, messages, excluded, unmatched)
        }
    }

    fun parseTime(s: String): Long {
        val t = s.trim()
        t.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        t.toDoubleOrNull()?.let { return (if (it < 1e11) it * 1000 else it).toLong() }
        return runCatching { Instant.parse(t).toEpochMilli() }.getOrElse {
            runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrDefault(0L)
        }
    }

    /** The suggested_topics.json format (priority 100 for the top topic, scaled down). */
    fun toJson(topics: List<Topic>): String {
        val max = topics.maxOfOrNull { it.score }?.takeIf { it > 0 } ?: 1.0
        return JSONObject().put("generated_from", "Grok data export (analysed on this phone)").put("topics", JSONArray().apply {
            topics.forEach { t ->
                put(JSONObject().put("title", t.title).put("category", t.category).put("emoji", t.emoji)
                    .put("priority", maxOf(1, Math.round(t.score / max * 100).toInt())).put("subtopics", JSONArray(t.subtopics)))
            }
        }).toString(2)
    }
}
