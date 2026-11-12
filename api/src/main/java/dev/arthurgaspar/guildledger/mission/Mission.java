package dev.arthurgaspar.guildledger.mission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import dev.arthurgaspar.guildledger.team.Membership;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "missions")
public class Mission {

    public enum Status {
        POSTED, PLANNED, DEPARTED, COMPLETED, FAILED, CANCELLED;

        public boolean isClosed() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String kind;

    @Column(name = "dungeon_code", nullable = false)
    private String dungeonCode;

    @Column(nullable = false)
    private int floor;

    @Column(nullable = false)
    private String difficulty;

    @Column(name = "client_species_id")
    private Integer clientSpeciesId;

    @Column(name = "reward_poke", nullable = false)
    private int rewardPoke;

    @Column(name = "rank_points", nullable = false)
    private int rankPoints;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mission_reward_items", joinColumns = @JoinColumn(name = "mission_id"))
    private List<ItemStack> rewardItems = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.POSTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leader_id")
    private Membership leader;

    @ElementCollection
    @CollectionTable(name = "mission_party", joinColumns = @JoinColumn(name = "mission_id"))
    @OrderColumn(name = "position")
    @Column(name = "recruit_id")
    private List<Long> party = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "mission_bag", joinColumns = @JoinColumn(name = "mission_id"))
    private List<ItemStack> bag = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "posted_by_id")
    private Membership postedBy;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @Column(name = "departed_at")
    private Instant departedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "outcome_note")
    private String outcomeNote;

    @Version
    private long version;

    protected Mission() {
    }

    Mission(Long teamId, String title, String kind, String dungeonCode, int floor, String difficulty,
            Integer clientSpeciesId, int rewardPoke, int rankPoints, List<ItemStack> rewardItems,
            Membership postedBy, Instant postedAt) {
        this.teamId = teamId;
        this.title = title;
        this.kind = kind;
        this.dungeonCode = dungeonCode;
        this.floor = floor;
        this.difficulty = difficulty;
        this.clientSpeciesId = clientSpeciesId;
        this.rewardPoke = rewardPoke;
        this.rankPoints = rankPoints;
        this.rewardItems = new ArrayList<>(rewardItems);
        this.postedBy = postedBy;
        this.postedAt = postedAt;
    }

    void plan(Membership leader, List<Long> party, List<ItemStack> bag) {
        this.leader = leader;
        this.party.clear();
        this.party.addAll(party);
        this.bag.clear();
        this.bag.addAll(bag);
        this.status = Status.PLANNED;
    }

    void depart(Instant at) {
        this.status = Status.DEPARTED;
        this.departedAt = at;
    }

    void close(Status outcome, String note, Instant at) {
        this.status = outcome;
        this.outcomeNote = note;
        this.closedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getTeamId() {
        return teamId;
    }

    public String getTitle() {
        return title;
    }

    public String getKind() {
        return kind;
    }

    public String getDungeonCode() {
        return dungeonCode;
    }

    public int getFloor() {
        return floor;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public Integer getClientSpeciesId() {
        return clientSpeciesId;
    }

    public int getRewardPoke() {
        return rewardPoke;
    }

    public int getRankPoints() {
        return rankPoints;
    }

    public List<ItemStack> getRewardItems() {
        return rewardItems;
    }

    public Status getStatus() {
        return status;
    }

    public Membership getLeader() {
        return leader;
    }

    public List<Long> getParty() {
        return party;
    }

    public List<ItemStack> getBag() {
        return bag;
    }

    public Membership getPostedBy() {
        return postedBy;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public Instant getDepartedAt() {
        return departedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getOutcomeNote() {
        return outcomeNote;
    }
}
