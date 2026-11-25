package com.project.souklab.dto.formation;

import com.project.souklab.model.Artisan;
import com.project.souklab.model.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data transfer object encapsulating authoring artisan details for masterclasses and formations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormationAuthorDTO {

    /**
     * Unique identifier of the authoring artisan.
     */
    private String id;

    /**
     * Full display name of the instructor artisan.
     */
    private String name;

    /**
     * Public avatar photograph URL of the instructor.
     */
    private String avatarUrl;

    /**
     * Geographic municipality or city where the artisan workshop resides.
     */
    private String city;

    /**
     * Accreditation flag certifying master teacher status.
     */
    private boolean teacher;

    /**
     * Factory method mapping an Artisan entity to a FormationAuthorDTO.
     *
     * @param artisan the artisan entity
     * @return populated FormationAuthorDTO
     */
    public static FormationAuthorDTO from(Artisan artisan) {
        return from(artisan, false);
    }

    public static FormationAuthorDTO from(Artisan artisan, boolean formateur) {
        if (artisan == null) {
            return null;
        }
        User user = artisan.getUser();
        String fullName = null;
        String avatar = null;
        if (user != null) {
            String first = user.getFirstName() != null ? user.getFirstName() : "";
            String last = user.getLastName() != null ? user.getLastName() : "";
            fullName = (first + " " + last).trim();
            if (fullName.isEmpty()) {
                fullName = user.getEmail();
            }
            avatar = user.getAvatarUrl();
        }
        return FormationAuthorDTO.builder()
                .id(artisan.getId())
                .name(fullName)
                .avatarUrl(avatar)
                .city(artisan.getCity())
                .teacher(formateur)
                .build();
    }
}
