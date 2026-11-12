package dev.arthurgaspar.guildledger.auth;

public record UserView(long id, String username, String displayName, int speciesId) {

    public static UserView of(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getSpeciesId());
    }
}
