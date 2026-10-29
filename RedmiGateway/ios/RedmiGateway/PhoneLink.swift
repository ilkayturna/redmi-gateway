import Foundation

// Pure rules behind the conversation screen. They import Foundation only, so a plain swiftc test
// (ios/Tests/PhoneLinkTests.swift) runs them without UIKit, a simulator or a phone.

/// Phone-line links inside a message.
///
/// This iPhone has no cellular service; its phone line is the Redmi. A tel: or sms: link handed to
/// iOS opens Apple's Phone or Messages app on the iPhone's own dead line, so every such link is
/// answered inside the app: calls go through InAppDial, messages to the app's own conversation.
enum PhoneLink {
    enum Route: Equatable {
        /// Call this number through the Redmi.
        case call(String)
        /// Open the conversation with this number.
        case message(String)
        /// A phone-line link without a number the Redmi could dial. It still must not reach iOS.
        case unusable

        var number: String? {
            switch self {
            case .call(let number), .message(let number): return number
            case .unusable: return nil
            }
        }
    }

    private static let callSchemes: Set<String> = ["tel", "telprompt"]
    private static let messageSchemes: Set<String> = ["sms"]

    /// nil for every link that has nothing to do with the phone line (http, mailto, maps, the
    /// data detectors' own links): those keep the system's action.
    static func route(for url: URL) -> Route? {
        guard let scheme = url.scheme?.lowercased() else { return nil }
        let isCall = callSchemes.contains(scheme)
        guard isCall || messageSchemes.contains(scheme) else { return nil }
        guard let found = number(fromLink: url.absoluteString) else { return .unusable }
        return isCall ? .call(found) : .message(found)
    }

    /// "tel:0532%20123%2045%2067" -> "05321234567".
    ///
    /// The data detectors percent-encode the spaces of the number as it was written, and
    /// CallKitManager.startOutgoingCall only strips plain separators, so the link is decoded here.
    static func number(fromLink link: String) -> String? {
        guard let colon = link.firstIndex(of: ":") else { return nil }
        var rest = link[link.index(after: colon)...]
        // Parameters, further recipients and pauses follow the first number (tel:...;ext=12,
        // sms:...?body=..., sms:111,222, tel:...,123). Only the number itself is dialled.
        if let end = rest.firstIndex(where: { "?;,&".contains($0) }) {
            rest = rest[..<end]
        }
        let raw = String(rest)
        return dialable(raw.removingPercentEncoding ?? raw)
    }

    /// What CallKitManager.startOutgoingCall accepts: 0-9, * and #, a + only in front, 2-20 long.
    ///
    /// A number written with letters (a vanity number, or a pause written as p/w) is refused as a
    /// whole: dialling only its digits would ring somebody else.
    static func dialable(_ text: String) -> String? {
        var result = ""
        for character in text {
            if character.isLetter { return nil }
            if (character.isASCII && character.isNumber) || character == "*" || character == "#" {
                result.append(character)
            } else if character == "+" && result.isEmpty {
                result.append(character)
            }
        }
        let digits = result.filter { $0 != "+" }.count
        guard digits >= 2, result.count <= 20 else { return nil }
        return result
    }

    /// Whether a conversation's address can be answered or called: the Redmi's own send check
    /// (SmsSender.normalizeRecipient, then `^\+?[0-9]{3,15}$`). "TURKCELL" and "BANK123" fail it, so
    /// neither the composer nor the call button is offered for them.
    static func isDialable(_ address: String) -> Bool {
        var cleaned = String.UnicodeScalarView()
        for scalar in address.unicodeScalars where !recipientSeparators.contains(scalar) {
            cleaned.append(scalar)
        }
        var digits = Substring(String(cleaned))
        if digits.hasPrefix("+") { digits = digits.dropFirst() }
        guard (3...15).contains(digits.unicodeScalars.count) else { return false }
        return digits.unicodeScalars.allSatisfy { $0.value >= 48 && $0.value <= 57 }
    }

    /// Kotlin's `[\s().-]` in SmsSender.normalizeRecipient.
    private static let recipientSeparators: Set<Unicode.Scalar> = [
        " ", "\t", "\n", "\u{0B}", "\u{0C}", "\r", "(", ")", ".", "-"
    ]
}

/// One-time codes in incoming SMS (banks, e-Devlet, WhatsApp, Google).
///
/// iOS AutoFill only reads codes from Apple's Messages and Mail, never from this app, so the
/// conversation offers "Kodu kopyala" instead. A code is a run of 4-8 digits ("482913"), or two
/// groups of three or four ("123-456", "123 456"), near a word such as kod, şifre, parola, code or
/// OTP. Amounts, dates, times, phone numbers, IBANs and masked card numbers are not codes. Codes
/// with letters are left to text selection: sender tags (B002) and campaign codes look the same,
/// and a wrong "Kodu kopyala" is worse than none.
enum OneTimeCode {
    /// Matched against the start of a word, so Turkish suffixes count: kodunuz, şifreniz.
    private static let keywords = [
        "kod", "code", "sifre", "parola", "dogrulama", "otp", "password", "passcode", "verification"
    ]
    private static let currencyWords: Set<String> = ["tl", "try", "usd", "eur", "euro", "gbp", "dolar", "lira"]
    private static let currencySigns: Set<Character> = ["\u{20BA}", "$", "\u{20AC}", "\u{00A3}"]
    /// Separators inside a number that is not a code: 1.250,00 / 12.10.2026 / 14:30 / 12/10.
    private static let numberSeparators: Set<Character> = [".", ",", ":", "/"]
    /// A number further than this from every keyword belongs to another sentence.
    private static let maxDistance = 60

    private struct Candidate {
        let code: String
        let span: Range<Int>
    }

    static func detect(in body: String) -> String? {
        let chars = Array(body)
        var keywordSpans: [Range<Int>] = []
        var runs: [Range<Int>] = []
        var index = 0
        while index < chars.count {
            let start = index
            if chars[index].isLetter {
                while index < chars.count && chars[index].isLetter { index += 1 }
                let word = fold(String(chars[start..<index]))
                if keywords.contains(where: { word.hasPrefix($0) }) { keywordSpans.append(start..<index) }
            } else if isDigit(chars[index]) {
                while index < chars.count && isDigit(chars[index]) { index += 1 }
                runs.append(start..<index)
            } else {
                index += 1
            }
        }
        guard !keywordSpans.isEmpty, !runs.isEmpty else { return nil }

        var best: (code: String, distance: Int)?
        for candidate in candidates(in: chars, runs: runs) {
            var distance = Int.max
            for keyword in keywordSpans {
                distance = min(distance, gap(keyword, candidate.span))
            }
            guard distance <= maxDistance else { continue }
            if let current = best, current.distance <= distance { continue }
            best = (candidate.code, distance)
        }
        return best?.code
    }

    /// Digit runs joined by a single space or hyphen form one number: a phone number, an IBAN or a
    /// card number spreads over several runs (and over nine digits), a code over one or two.
    private static func candidates(in chars: [Character], runs: [Range<Int>]) -> [Candidate] {
        var groups: [[Range<Int>]] = []
        for run in runs {
            if let previous = groups.last?.last, run.lowerBound == previous.upperBound + 1,
               chars[previous.upperBound] == " " || chars[previous.upperBound] == "-" {
                groups[groups.count - 1].append(run)
            } else {
                groups.append([run])
            }
        }

        var result: [Candidate] = []
        for group in groups {
            let total = group.reduce(0) { $0 + $1.count }
            guard total <= 8, let first = group.first, let last = group.last else { continue }
            if group.count == 2, group.allSatisfy({ (3...4).contains($0.count) }) {
                let span = first.lowerBound..<last.upperBound
                if isCodeShaped(span, in: chars) {
                    result.append(Candidate(code: group.map { String(chars[$0]) }.joined(), span: span))
                }
                continue
            }
            for run in group where (4...8).contains(run.count) && isCodeShaped(run, in: chars) {
                result.append(Candidate(code: String(chars[run]), span: run))
            }
        }
        return result
    }

    /// Rejects digits that belong to something else: a letter right before them (XXXX1234, B002),
    /// a mask (****1234), a decimal, date or time around them, or a currency next to them.
    private static func isCodeShaped(_ span: Range<Int>, in chars: [Character]) -> Bool {
        let start = span.lowerBound, end = span.upperBound
        if start > 0 {
            let previous = chars[start - 1]
            if previous.isLetter || previous == "*" || previous == "\u{2022}" { return false }
            if start > 1 && numberSeparators.contains(previous) && isDigit(chars[start - 2]) { return false }
        }
        if end + 1 < chars.count && numberSeparators.contains(chars[end]) && isDigit(chars[end + 1]) {
            return false
        }
        // "2500 TL", "2500TL", "2500 ₺"
        var after = end
        if after < chars.count && chars[after] == " " { after += 1 }
        if after < chars.count {
            if currencySigns.contains(chars[after]) { return false }
            var wordEnd = after
            while wordEnd < chars.count && chars[wordEnd].isLetter { wordEnd += 1 }
            if wordEnd > after && currencyWords.contains(fold(String(chars[after..<wordEnd]))) { return false }
        }
        // "₺2500", "TL 2500"
        var before = start - 1
        if before >= 0 && chars[before] == " " { before -= 1 }
        if before >= 0 {
            if currencySigns.contains(chars[before]) { return false }
            var wordStart = before + 1
            while wordStart > 0 && chars[wordStart - 1].isLetter { wordStart -= 1 }
            if wordStart <= before && currencyWords.contains(fold(String(chars[wordStart...before]))) { return false }
        }
        return true
    }

    private static func gap(_ a: Range<Int>, _ b: Range<Int>) -> Int {
        if b.lowerBound >= a.upperBound { return b.lowerBound - a.upperBound }
        if a.lowerBound >= b.upperBound { return a.lowerBound - b.upperBound }
        return 0
    }

    private static func isDigit(_ character: Character) -> Bool {
        character.isASCII && character.isNumber
    }

    /// Lower case without Turkish marks, so ŞİFRE, Şifre and sifre all read "sifre".
    private static func fold(_ word: String) -> String {
        var out = ""
        for scalar in word.lowercased().unicodeScalars {
            switch scalar {
            case "\u{0131}": out.append("i") // ı
            case "\u{015F}": out.append("s") // ş
            case "\u{011F}": out.append("g") // ğ
            case "\u{00FC}": out.append("u") // ü
            case "\u{00F6}": out.append("o") // ö
            case "\u{00E7}": out.append("c") // ç
            // Combining dot above (İ lower-cases to i + U+0307), cedilla, breve, diaeresis.
            case "\u{0307}", "\u{0327}", "\u{0306}", "\u{0308}": continue
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out
    }
}

/// What a draft costs in SMS parts, the way Android's SmsManager.divideMessage splits it on the
/// Redmi.
///
/// GSM 03.38 text takes 160 septets in one part and 153 per part once split; characters of the
/// extension table ([ ] { } ~ \ | ^ € and form feed) take two septets and never straddle a part.
/// Any other character (ş, ğ, ı, ç, emoji) turns the whole message into UCS-2: 70 UTF-16 units in
/// one part, 67 per part, and Android does not split a character across parts. A carrier can
/// enable the Turkish shift tables on the Redmi, which makes ş, ğ and ı cheaper; this count is then
/// an upper bound, the safe side for a cost warning.
struct SmsLength: Equatable {
    let parts: Int
    /// Septets for GSM-7 text, UTF-16 units for UCS-2.
    let units: Int
    /// What the shown parts can hold: 160 or 70 for one part, 153 or 67 per part otherwise.
    let capacity: Int
    let isUnicode: Bool

    init(_ text: String) {
        if let costs = SmsLength.gsmCosts(text) {
            let total = costs.reduce(0, +)
            let parts = total <= 160 ? 1 : SmsLength.pack(costs, per: 153)
            self.parts = parts
            units = total
            capacity = parts == 1 ? 160 : parts * 153
            isUnicode = false
        } else {
            let costs = text.map { $0.utf16.count }
            let total = costs.reduce(0, +)
            let parts = total <= 70 ? 1 : SmsLength.pack(costs, per: 67)
            self.parts = parts
            units = total
            capacity = parts == 1 ? 70 : parts * 67
            isUnicode = true
        }
    }

    /// Fills parts in order without splitting one character; one longer than a whole part (a very
    /// long emoji sequence) spills over the way Android cuts it.
    private static func pack(_ costs: [Int], per capacity: Int) -> Int {
        var parts = 1
        var used = 0
        for cost in costs {
            if used > 0 && used + cost > capacity {
                parts += 1
                used = 0
            }
            used += cost
            while used > capacity {
                parts += 1
                used -= capacity
            }
        }
        return parts
    }

    /// Septets per character, or nil when one of them is outside GSM 03.38.
    private static func gsmCosts(_ text: String) -> [Int]? {
        var costs: [Int] = []
        for scalar in text.unicodeScalars {
            if gsmBasic.contains(scalar) {
                costs.append(1)
            } else if gsmExtension.contains(scalar) {
                costs.append(2)
            } else {
                return nil
            }
        }
        return costs
    }

    /// The default alphabet in table order (Android GsmAlphabet). 0x1B is the escape, not a
    /// character. There is no lower-case ç: 0x09 is Ç.
    private static let gsmBasic: Set<Unicode.Scalar> = Set([
        "@\u{00A3}$\u{00A5}\u{00E8}\u{00E9}\u{00F9}\u{00EC}\u{00F2}\u{00C7}\n\u{00D8}\u{00F8}\r\u{00C5}\u{00E5}",
        "\u{0394}_\u{03A6}\u{0393}\u{039B}\u{03A9}\u{03A0}\u{03A8}\u{03A3}\u{0398}\u{039E}\u{00C6}\u{00E6}\u{00DF}\u{00C9}",
        " !\"#\u{00A4}%&'()*+,-./0123456789:;<=>?",
        "\u{00A1}ABCDEFGHIJKLMNOPQRSTUVWXYZ\u{00C4}\u{00D6}\u{00D1}\u{00DC}\u{00A7}",
        "\u{00BF}abcdefghijklmnopqrstuvwxyz\u{00E4}\u{00F6}\u{00F1}\u{00FC}\u{00E0}"
    ].joined().unicodeScalars)

    /// The extension table: each of these is an escape plus one septet.
    private static let gsmExtension: Set<Unicode.Scalar> = Set("\u{0C}^{}\\[~]|\u{20AC}".unicodeScalars)
}

/// Messages made of one to three emoji, which Messages shows large and without a bubble.
enum EmojiText {
    static func jumboCount(_ text: String) -> Int? {
        let emoji = text.filter { !$0.isWhitespace }
        guard (1...3).contains(emoji.count), emoji.allSatisfy({ isEmoji($0) }) else { return nil }
        return emoji.count
    }

    static func isEmoji(_ character: Character) -> Bool {
        let scalars = character.unicodeScalars
        guard let first = scalars.first else { return false }
        if first.properties.isEmojiPresentation { return true }
        // Text-style symbols (the plain heart, digits, #) are emoji only with VS16 or a keycap mark.
        return first.properties.isEmoji && scalars.contains { $0.value == 0xFE0F || $0.value == 0x20E3 }
    }
}
