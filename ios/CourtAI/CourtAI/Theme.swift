import SwiftUI

enum CourtTheme {
    static let ink = Color(red: 0.043, green: 0.071, blue: 0.125)
    static let navy = Color(red: 0.075, green: 0.133, blue: 0.220)
    static let orange = Color(red: 1.0, green: 0.416, blue: 0.0)
    static let amber = Color(red: 1.0, green: 0.690, blue: 0.125)
    static let cream = Color(red: 0.969, green: 0.945, blue: 0.910)
    static let mint = Color(red: 0.176, green: 0.831, blue: 0.659)
    static let muted = Color(red: 0.608, green: 0.690, blue: 0.788)
}

struct CourtBackground: View {
    var body: some View {
        LinearGradient(
            colors: [CourtTheme.ink, CourtTheme.navy, Color(red: 0.106, green: 0.227, blue: 0.184)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    var color: Color = CourtTheme.orange
    var foreground: Color = .white

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(foreground)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(color.opacity(configuration.isPressed ? 0.8 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

struct OutlineButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(CourtTheme.cream)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(CourtTheme.navy.opacity(configuration.isPressed ? 0.5 : 0.85))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(Color.white.opacity(0.15), lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
