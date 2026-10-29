import Foundation

// A standalone executable: no UIKit, simulator or phone.
// Compile this file together with the production PhoneLink.swift.
@main
struct PhoneLinkTests {
    static var failures = 0
    static var checks = 0

    static func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
        checks += 1
        if !condition() { failures += 1; print("FAIL: \(message)") }
    }

    static func main() {
        phoneLinksStayOnTheRedmi()
        otherLinksKeepTheSystemAction()
        unusableNumbersNeverReachIOS()
        detectedTextFallbackIgnoresNonNumbers()
        replyNeedsADialableAddress()
        oneTimeCodesAreFound()
        numbersThatAreNotCodesAreIgnored()
        gsmTextCountsSeptets()
        turkishLettersAndEmojiCountUtf16()
        jumboEmojiAreRecognised()
        if failures != 0 { print("\(failures) phone link regression(s)"); exit(1) }
        print("PASS: \(checks) phone link, one-time code and SMS length checks")
    }

    static func route(_ link: String) -> PhoneLink.Route? {
        guard let url = URL(string: link) else {
            failures += 1
            print("FAIL: test link does not parse: \(link)")
            return nil
        }
        return PhoneLink.route(for: url)
    }

    // Break caught: handing the data detector's tel: URL to iOS, which calls on the iPhone's own
    // dead line, or passing it on still percent-encoded, which startOutgoingCall refuses.
    static func phoneLinksStayOnTheRedmi() {
        expect(route("tel:0532%20123%2045%2067") == .call("05321234567"), "spaces of a detected number are decoded")
        expect(route("telprompt:+90%20532%20123%2045%2067") == .call("+905321234567"), "telprompt keeps the plus")
        expect(route("TEL:444%200%20532") == .call("4440532"), "scheme case does not matter")
        expect(route("tel:+90-532-123-45-67;ext=12") == .call("+905321234567"), "parameters are not dialled")
        expect(route("tel://05321234567") == .call("05321234567"), "slashes after the scheme are ignored")
        expect(route("tel:*123%23") == .call("*123#"), "service codes keep * and #")
        expect(route("tel:112") == .call("112"), "emergency numbers go to InAppDial, which hands them to iOS")
        expect(route("sms:+905321234567?body=Merhaba%20123") == .message("+905321234567"), "sms body digits are not dialled")
        expect(route("sms:05321234567&body=x") == .message("05321234567"), "sms & body form")
        expect(route("sms:05321234567,05551112233") == .message("05321234567"), "first sms recipient")
        expect(PhoneLink.Route.call("05321234567").number == "05321234567", "route exposes its number")
        expect(PhoneLink.Route.unusable.number == nil, "unusable route has no number")
    }

    static func otherLinksKeepTheSystemAction() {
        expect(route("https://www.turkcell.com.tr/05321234567") == nil, "web links open normally")
        expect(route("mailto:destek@example.com") == nil, "mail links open normally")
        expect(route("x-apple-data-detectors://3") == nil, "data detector links are not phone links by scheme")
        expect(route("facetime://05321234567") == nil, "FaceTime works over the hotspot and is left alone")
    }

    // Break caught: dialling only the digits of a number written with letters, or returning nil
    // for a phone-line link and so letting iOS open it.
    static func unusableNumbersNeverReachIOS() {
        expect(route("tel:1-800-FLOWERS") == .unusable, "vanity numbers are refused, not cut to their digits")
        expect(route("tel:%20") == .unusable, "empty number")
        expect(route("tel:1") == .unusable, "one digit is not a number")
        expect(route("tel:123456789012345678901") == .unusable, "longer than startOutgoingCall allows")
        expect(route("sms:?body=x") == .unusable, "sms without a recipient")
        expect(PhoneLink.number(fromLink: "no scheme") == nil, "text without a scheme")
        expect(PhoneLink.dialable("+90 (532) 123-45-67") == "+905321234567", "separators are dropped")
        expect(PhoneLink.dialable("0532+123") == "0532123", "a plus inside the number is dropped")
    }

    static func detectedTextFallbackIgnoresNonNumbers() {
        expect(PhoneLink.route(forDetectedText: "") == nil, "empty detected text")
        expect(PhoneLink.route(forDetectedText: "Atatürk Caddesi No 5 Kadıköy") == nil, "an address is not a number")
        expect(PhoneLink.route(forDetectedText: "yarın 14:30") == nil, "a date is not a number")
    }

    // Break caught: offering the call button and the composer to "TURKCELL" or "BANK123", which
    // both the iPhone's dial check and the Redmi's send check refuse.
    static func replyNeedsADialableAddress() {
        expect(PhoneLink.isDialable("+90 532 123 45 67"), "international mobile")
        expect(PhoneLink.isDialable("0532 (123) 45-67"), "typed with separators")
        expect(PhoneLink.isDialable("3434"), "short code")
        expect(!PhoneLink.isDialable("TURKCELL"), "operator name")
        expect(!PhoneLink.isDialable("BANK123"), "letters and digits")
        expect(!PhoneLink.isDialable("12"), "too short")
        expect(!PhoneLink.isDialable("+1234567890123456"), "longer than E.164")
        expect(!PhoneLink.isDialable(""), "empty")
        expect(!PhoneLink.isDialable("+"), "plus only")
    }

    static func oneTimeCodesAreFound() {
        let cases: [(String, String)] = [
            ("Garanti BBVA internet \u{015F}ifreniz: 482913. Bu \u{015F}ifreyi kimseyle payla\u{015F}may\u{0131}n\u{0131}z.", "482913"),
            ("123456 do\u{011F}rulama kodunuzdur. B002", "123456"),
            ("\u{015E}\u{0130}FREN\u{0130}Z 5821", "5821"),
            ("Tek kullan\u{0131}ml\u{0131}k \u{015F}ifreniz 482913't\u{00FC}r.", "482913"),
            ("Your WhatsApp code: 123-456", "123456"),
            ("G-482913 is your Google verification code.", "482913"),
            ("Instagram code 123 456", "123456"),
            ("12.10.2026 tarihli 1.250,00 TL i\u{015F}leminiz i\u{00E7}in do\u{011F}rulama kodunuz: 774411", "774411"),
            ("\u{015E}ifreniz: 123456 3 dk ge\u{00E7}erlidir", "123456"),
            ("Do\u{011F}rulama kodunuz 4821. Bu kodu 5 dakika i\u{00E7}inde kullan\u{0131}n. Ref: 99231", "4821"),
            ("e-Devlet Kap\u{0131}s\u{0131} giri\u{015F} OTP: 90817263", "90817263"),
            ("Kodunuz:\n664422", "664422")
        ]
        for (body, code) in cases {
            let found = OneTimeCode.detect(in: body)
            expect(found == code, "code \(code) in \"\(body)\" (found \(found ?? "nil"))")
        }
    }

    // Break caught: offering "Kodu kopyala" for an amount, a date, a phone number, an IBAN, a
    // masked card or a sender tag.
    static func numbersThatAreNotCodesAreIgnored() {
        let farFromKeyword = "Kod " + String(repeating: "a ", count: 40) + "1234"
        let bodies: [String] = [
            "Merhaba, yar\u{0131}n 14:30'da g\u{00F6}r\u{00FC}\u{015F}elim 2026",
            "Kodunuz 2500 TL de\u{011F}erinde hediye \u{00E7}ekidir",
            "Kampanya kodu ile 1500TL indirim",
            "Kod ile \u{20BA}1500 kazan",
            "Do\u{011F}rulama kodu i\u{00E7}in 0532 123 45 67 numaras\u{0131}n\u{0131} aray\u{0131}n",
            "IBAN: TR12 0006 2000 1234 5678 9012 34 \u{015F}ifre",
            "Kart\u{0131}n\u{0131}z ****1234 ile \u{015F}ifre talebi",
            "Kod: B002",
            "kodunuz:XXXX1234",
            "Hesap \u{015F}ifreniz 1234567890",
            "Sipari\u{015F}iniz 12.10.2026 tarihinde kargoya verildi",
            farFromKeyword,
            "\u{015E}ifre de\u{011F}i\u{015F}ikli\u{011F}i 29/10/2026 14:05"
        ]
        for body in bodies {
            let found = OneTimeCode.detect(in: body)
            expect(found == nil, "no code in \"\(body)\" (found \(found ?? "nil"))")
        }
    }

    static func gsmTextCountsSeptets() {
        let empty = SmsLength("")
        expect(empty.parts == 1 && empty.units == 0 && !empty.isUnicode, "empty draft")
        let full = SmsLength(String(repeating: "a", count: 160))
        expect(full.parts == 1 && full.units == 160 && full.capacity == 160, "160 GSM characters fit one part")
        let split = SmsLength(String(repeating: "a", count: 161))
        expect(split.parts == 2 && split.capacity == 306, "161 GSM characters need two parts of 153")
        expect(SmsLength(String(repeating: "a", count: 306)).parts == 2, "306 GSM characters fill two parts")
        expect(SmsLength(String(repeating: "a", count: 307)).parts == 3, "307 GSM characters need a third")
        // ö, ü, Ö, Ü, Ç, é, à and £ are GSM letters although they are not ASCII.
        let accented = SmsLength("\u{00F6}\u{00FC}\u{00D6}\u{00DC}\u{00C7}\u{00E9}\u{00E0}\u{00A3}")
        expect(!accented.isUnicode && accented.units == 8, "GSM letters outside ASCII stay 7-bit")
        expect(SmsLength("\u{20AC}").units == 2, "the euro sign takes two septets")
        expect(SmsLength("[]{}").units == 8, "extension characters take two septets each")
        expect(SmsLength("`").isUnicode, "the backtick is ASCII but not GSM")
        expect(SmsLength("\u{00E7}").isUnicode, "lower-case c cedilla is not in the GSM table")
        // An escape pair never straddles two parts.
        let straddle = SmsLength(String(repeating: "a", count: 152) + "\u{20AC}" + String(repeating: "a", count: 10))
        expect(straddle.parts == 2 && straddle.units == 164, "escape pair moves to the next part")
        expect(SmsLength("Merhaba\r\nnas\u{0131}ls\u{0131}n").isUnicode, "dotless i is UCS-2")
    }

    // Break caught: counting Characters instead of UTF-16 units, and showing the counter only after
    // 140 characters while Turkish text splits at 70.
    static func turkishLettersAndEmojiCountUtf16() {
        let short = SmsLength("Ya\u{011F}mur ya\u{011F}\u{0131}yor")
        expect(short.isUnicode && short.parts == 1 && short.capacity == 70, "Turkish letters use UCS-2")
        expect(SmsLength(String(repeating: "\u{015F}", count: 70)).parts == 1, "70 UCS-2 units fit one part")
        let two = SmsLength(String(repeating: "\u{015F}", count: 71))
        expect(two.parts == 2 && two.capacity == 134, "71 UCS-2 units need two parts of 67")
        let hundred = SmsLength(String(repeating: "\u{015F}", count: 100))
        expect(hundred.parts == 2 && hundred.units == 100, "a 100-character Turkish message is two SMS")
        let emoji = SmsLength("\u{1F600}")
        expect(emoji.isUnicode && emoji.units == 2, "an emoji is two UTF-16 units")
        expect(SmsLength(String(repeating: "\u{1F600}", count: 35)).parts == 1, "35 emoji fill one part")
        // 33 emoji (66 units) fit a 67-unit part; the 34th never splits.
        expect(SmsLength(String(repeating: "\u{1F600}", count: 36)).parts == 2, "36 emoji need two parts")
        expect(SmsLength(String(repeating: "\u{1F600}", count: 67)).parts == 3, "67 emoji need three parts")
    }

    static func jumboEmojiAreRecognised() {
        expect(EmojiText.jumboCount("\u{1F44D}") == 1, "one emoji")
        expect(EmojiText.jumboCount("\u{1F600}\u{1F600}\u{1F600}") == 3, "three emoji")
        expect(EmojiText.jumboCount(" \u{1F44D} \u{1F44D} ") == 2, "spaces around emoji")
        expect(EmojiText.jumboCount("\u{2764}\u{FE0F}") == 1, "red heart with VS16")
        expect(EmojiText.jumboCount("\u{1F1F9}\u{1F1F7}") == 1, "flag")
        expect(EmojiText.jumboCount("\u{1F44D}\u{1F3FD}") == 1, "skin tone")
        expect(EmojiText.jumboCount("\u{1F600}\u{1F600}\u{1F600}\u{1F600}") == nil, "four emoji are a normal message")
        expect(EmojiText.jumboCount("\u{1F600} tamam") == nil, "emoji with text")
        expect(EmojiText.jumboCount("1") == nil, "a digit is not an emoji")
        expect(EmojiText.jumboCount("123") == nil, "digits are not emoji")
        expect(EmojiText.jumboCount("#") == nil, "hash is not an emoji")
        expect(EmojiText.jumboCount("\u{00A9}") == nil, "copyright sign without VS16")
        expect(EmojiText.jumboCount("") == nil, "empty")
    }
}
